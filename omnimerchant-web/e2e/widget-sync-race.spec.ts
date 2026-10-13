import { expect, test } from '@playwright/test'

// 前端时序桩测试：模拟快速落库及尚未返回的聊天请求，不作为真实后端端到端证据。
test.use({ channel: 'msedge', trace: 'off', screenshot: 'off', actionTimeout: 15_000 })
test('快速落库不重复显示 AI，流式期间接管仍持续拉取人工消息', async ({ page }) => {
  const fixture: { messageUuid: string; seqNo: number; role: string; source: string; content: string }[] = []
  let calls = 0, human = false
  let release!: () => void
  const blocked = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/widget/session', route => route.fulfill({ json: { code: '200', data: {
    tenantCode: '时序测试店铺', tenantId: 1001, storeName: '时序测试店铺', conversationUuid: '时序测试会话',
    customerSessionToken: 'frontend-test-token', expiresAt: new Date(Date.now() + 60000).toISOString(),
  } } }))
  await page.route('**/api/widget/messages?**', route => {
    const cursor = Number(new URL(route.request().url()).searchParams.get('afterSeqNo'))
    return route.fulfill({ json: { code: '200', data: { messages: fixture.filter(m => m.seqNo > cursor), hasMore: false, conversationStatus: human ? 4 : 1 } } })
  })
  await page.route('**/api/widget/chat/stream', async route => {
    calls++
    fixture.push({ messageUuid: `用户-${calls}`, seqNo: fixture.length + 1, role: 'user', source: 'USER', content: '相同问题' })
    if (calls === 3) {
      await blocked
      await route.fulfill({ contentType: 'text/event-stream', body: 'event:status\ndata:已转人工\n\n' }).catch(() => {})
      return
    }
    fixture.push({ messageUuid: `模型-${calls}`, seqNo: fixture.length + 1, role: 'assistant', source: 'AI', content: '相同回答' })
    await route.fulfill({ contentType: 'text/event-stream', body: 'event:translated_delta\ndata:相同回答\n\nevent:final\ndata:相同回答\n\n' })
  })
  await page.goto('/widget')
  await page.getByPlaceholder('店铺编码').fill('时序测试店铺')
  await page.getByRole('button', { name: '开始咨询', exact: true }).click()
  await expect(page.getByText('已连接', { exact: true })).toBeVisible()
  await page.evaluate(() => {
    (window as any).__peakAi = 0
    const observer = new MutationObserver(() => {
      const count = Array.from(document.querySelectorAll('.msg.assistant .message-source')).filter(e => e.textContent?.trim() === 'AI 客服').length
      ;(window as any).__peakAi = Math.max((window as any).__peakAi, count)
    })
    observer.observe(document.querySelector('.messages')!, { childList: true, subtree: true, characterData: true })
  })
  for (let i = 1; i <= 2; i++) {
    await page.getByPlaceholder('请输入你的问题').fill('相同问题')
    await page.getByRole('button', { name: /发\s*送/ }).click()
    await expect(page.locator('.msg.assistant .message-source')).toHaveCount(i)
    await expect(page.getByText('已连接', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => (window as any).__peakAi)).toBe(i)
  }
  await page.getByPlaceholder('请输入你的问题').fill('相同问题')
  await page.getByRole('button', { name: /发\s*送/ }).click()
  await expect(page.getByText('回复中', { exact: true })).toBeVisible()
  await expect.poll(() => calls).toBe(3)
  fixture.push({ messageUuid: '人工-1', seqNo: fixture.length + 1, role: 'assistant', source: 'HUMAN', content: '流式期间的人工回复' })
  human = true
  try {
    await expect(page.getByText('流式期间的人工回复', { exact: true })).toBeVisible({ timeout: 8000 })
    await expect(page.getByPlaceholder('请输入你的问题')).toBeEnabled()
    await expect(page.locator('.msg.user')).toHaveCount(3)
    await expect(page.locator('.msg.assistant .message-source').filter({ hasText: 'AI 客服' })).toHaveCount(2)
    await expect(page.getByText('人工客服', { exact: true })).toBeVisible()
  } finally { release() }
})
