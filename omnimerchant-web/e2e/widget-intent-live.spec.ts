import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'

// 此用例会调用真实模型、创建测试会话及待审批申请，仅在显式启用时执行。
test.skip(process.env.OMNI_WIDGET_LIVE !== '1', '未启用真实 Widget 联调')
test.use({ channel: process.env.OMNI_BROWSER_CHANNEL || 'msedge' })

function query(sql: string): any[] {
  if (!process.env.OMNI_DB_PASSWORD) throw new Error('真实联调需要配置测试数据库密码')
  const text = execFileSync(process.env.OMNI_MYSQL_BIN || 'mysql', [
    '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '--raw',
    '-h', process.env.OMNI_DB_HOST || '127.0.0.1', '-u', process.env.OMNI_DB_USER || 'root',
    process.env.OMNI_DB_NAME || 'mini-omni-merchant', '-e', sql,
  ], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.OMNI_DB_PASSWORD } })
  return text.trim() ? text.trim().split(/\r?\n/).map(line => JSON.parse(line)) : []
}

test('真实 Widget 自动分类、工具执行、消息与轨迹逐项核验', async ({ page }, testInfo) => {
  test.setTimeout(600_000)
  const evidence: any[] = []
  let conversationUuid = ''
  // 只复制真实响应用于核验，不拦截、不替换请求或模型结果。
  // Chromium 不缓存 SSE 的响应体，因此从浏览器自身的流读取副本。
  await page.addInitScript(() => {
    const actualFetch = window.fetch.bind(window)
    window.fetch = async (...args) => {
      const response = await actualFetch(...args)
      if (String(args[0]).includes('/api/widget/chat/stream')) {
        ;(window as any).__widgetSse = response.clone().text()
      }
      return response
    }
  })

  async function session(tenantCode = 'OM-1002') {
    await page.goto('/widget')
    await page.getByPlaceholder('店铺编码').fill(tenantCode)
    const response = page.waitForResponse(r => r.url().endsWith('/api/widget/session') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '开始咨询' }).click()
    const body = await (await response).json()
    expect(body.code).toBe('200')
    conversationUuid = body.data.conversationUuid
    expect(conversationUuid).toMatch(/^[a-f0-9-]{36}$/)
    await expect(page.getByText('已连接', { exact: true })).toBeVisible()
    await expect(page.getByText('咨询类型', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('combobox')).toHaveCount(0)
  }

  async function send(scene: string, message: string, intent: string, requiredTools: string[]) {
    const before = query("SELECT JSON_OBJECT('count',COUNT(*)) FROM return_request")[0].count
    await page.getByPlaceholder('请输入你的问题').fill(message)
    const received = page.waitForResponse(r => r.url().endsWith('/api/widget/chat/stream') && r.request().method() === 'POST', { timeout: 45_000 })
    await page.getByRole('button', { name: /发\s*送/ }).click()
    const response = await received
    expect(response.request().postDataJSON()).not.toHaveProperty('intent')
    expect(response.status()).toBe(200)
    await expect(page.getByText('已连接', { exact: true })).toBeVisible({ timeout: 90_000 })
    const sse = await page.evaluate(async () => await (window as any).__widgetSse)
    const runs = query(`SELECT JSON_OBJECT('traceId',trace_id,'intent',intent,'status',status,'model',model_name) FROM agent_run WHERE conversation_uuid='${conversationUuid}' ORDER BY id DESC LIMIT 1`)
    expect(runs).toHaveLength(1)
    const run = runs[0]
    expect(run.traceId).toMatch(/^[a-f0-9]{32}$/)
    const steps = query(`SELECT JSON_OBJECT('type',step_type,'name',name,'status',status,'latencyMs',latency_ms,'output',output_summary,'metadata',metadata_json) FROM agent_step WHERE trace_id='${run.traceId}' ORDER BY step_index`)
    const messages = query(`SELECT JSON_OBJECT('role',role,'content',content) FROM chat_message WHERE conversation_uuid='${conversationUuid}' ORDER BY seq_no DESC LIMIT 2`).reverse()
    const after = query("SELECT JSON_OBJECT('count',COUNT(*)) FROM return_request")[0].count
    const record = { scene, conversationUuid, ...run, steps, messages, returnRequestDelta: after - before, sse }
    evidence.push(record)
    writeFileSync(testInfo.outputPath('widget-intent-evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(JSON.stringify({ scene, intent: run.intent, status: run.status, traceId: run.traceId, tools: steps.filter(s => s.type === 'TOOL').map(s => s.name), returnRequestDelta: after - before }))
    expect(run.intent).toBe(intent)
    expect(run.status).toBe('SUCCESS')
    expect(sse).toContain('event:final')
    expect(sse).not.toContain('event:error')
    const classification = steps.find(s => s.type === 'INTENT_CLASSIFICATION')
    expect(classification.status).toBe('SUCCESS')
    expect(classification.latencyMs).toBeGreaterThanOrEqual(0)
    const router = steps.find(s => s.type === 'ROUTER')
    const allowed = (typeof router.metadata === 'string' ? JSON.parse(router.metadata) : router.metadata).toolAllowlist
    const actual = steps.filter(s => s.type === 'TOOL').map(s => s.name)
    for (const tool of requiredTools) expect(actual).toContain(tool)
    for (const tool of actual) expect(allowed).toContain(tool)
    expect(messages.map(m => m.role)).toEqual(['user', 'assistant'])
    expect(messages[0].content).toBe(message)
    // 页面会渲染 Markdown，使用正文片段核验显示内容，避免把格式符当作正文。
    const preview = messages[1].content.match(/[\u4e00-\u9fff]{4,}/)?.[0]
    expect(preview).toBeTruthy()
    await expect(page.locator('.msg.assistant').last()).toContainText(preview)
    if (intent === 'POLICY_QA') {
      expect(allowed).toEqual(['refundPolicyRAG'])
      expect(after).toBe(before)
    }
    if (intent === 'RETURN_REFUND') {
      expect(after - before).toBe(1)
      expect(steps.filter(s => s.type === 'TOOL').some(s => s.output?.includes('PENDING_HUMAN_APPROVAL'))).toBe(true)
    }
  }

  await session()
  await send('商品推荐', '推荐一款蓝牙耳机', 'PRODUCT_ADVICE', ['searchProductCatalog'])
  await session()
  await send('订单查询', '我的订单 #2002 发货了吗？下单邮箱是 zhoutao@example.com', 'ORDER_STATUS', ['queryOrder'])
  await send('物流追问', '那它现在到哪里了？', 'LOGISTICS', ['trackLogistics'])
  await session('OM-1001')
  await send('退货政策', '退货期限是多少天？', 'POLICY_QA', ['refundPolicyRAG'])
  await session()
  await send('退款资格咨询', '我可以申请退款吗？', 'POLICY_QA', ['refundPolicyRAG'])
  await session()
  await send('明确申请退款', '请帮我提交订单 #2003 的退款申请，下单邮箱是 wumin@example.com，原因是商品损坏。', 'RETURN_REFUND', ['requestRefundOrReplacement'])
  await session()
  await send('转人工客服', '我要转人工客服', 'HUMAN_REQUEST', ['escalateToHuman'])
  await page.screenshot({ path: testInfo.outputPath('widget-natural-language.png'), fullPage: true })
})
