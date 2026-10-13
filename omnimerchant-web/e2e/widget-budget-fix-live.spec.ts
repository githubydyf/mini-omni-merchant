import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'

// 显式启用真实模型与数据库联调；证据仅保留诊断字段，不记录令牌及客户联系信息。
test.skip(process.env.OMNI_WIDGET_BUDGET_FIX_LIVE !== '1', '未启用额度修复真实联调')
test.use({ channel: process.env.OMNI_BROWSER_CHANNEL || 'msedge', trace: 'off', screenshot: 'off' })

function query(sql: string): any[] {
  if (!process.env.OMNI_DB_PASSWORD) throw new Error('需要配置本地测试数据库密码')
  const result = execFileSync(process.env.OMNI_MYSQL_BIN || 'mysql', [
    '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '--raw',
    '-h', '127.0.0.1', '-u', process.env.OMNI_DB_USER || 'root',
    process.env.OMNI_DB_NAME || 'mini-omni-merchant', '-e', sql,
  ], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.OMNI_DB_PASSWORD } })
  return result.trim() ? result.trim().split(/\r?\n/).map(line => JSON.parse(line)) : []
}

async function start(page: any, url: string) {
  // 仅观察真实 SSE 的副本，不替换请求、模型或业务工具。
  await page.addInitScript(() => {
    const original = window.fetch.bind(window)
    window.fetch = async (...args) => {
      const response = await original(...args)
      if (String(args[0]).includes('/api/widget/chat/stream')) (window as any).__realSse = response.clone().text()
      return response
    }
  })
  await page.goto(url)
  await page.getByPlaceholder('店铺编码').fill('OM-1001')
  const received = page.waitForResponse((r: any) => r.url().endsWith('/api/widget/session') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '开始咨询' }).click()
  const body = await (await received).json()
  expect(body.code).toBe('200')
  const uuid = body.data.conversationUuid
  expect(uuid).toMatch(/^[a-f0-9-]{36}$/)
  await expect(page.getByText('已连接', { exact: true })).toBeVisible()
  return uuid
}

async function send(page: any, uuid: string, message: string) {
  await page.getByPlaceholder('请输入你的问题').fill(message)
  const received = page.waitForResponse((r: any) => r.url().endsWith('/api/widget/chat/stream') && r.request().method() === 'POST', { timeout: 45_000 })
  await page.getByRole('button', { name: /发\s*送/ }).click()
  expect((await received).status()).toBe(200)
  await expect(page.getByText('已连接', { exact: true })).toBeVisible({ timeout: 90_000 })
  const sse = await page.evaluate(async () => await (window as any).__realSse)
  const run = query(`SELECT JSON_OBJECT('traceId',trace_id,'intent',intent,'status',status,'failureCategory',failure_category,'model',model_name) FROM agent_run WHERE conversation_uuid='${uuid}' ORDER BY id DESC LIMIT 1`)[0]
  expect(run.traceId).toMatch(/^[a-f0-9]{32}$/)
  const steps = query(`SELECT JSON_OBJECT('type',step_type,'name',name,'status',status,'category',failure_category,'metadata',metadata_json) FROM agent_step WHERE trace_id='${run.traceId}' ORDER BY step_index`)
  const count = query(`SELECT JSON_OBJECT('user',SUM(role='user'),'assistant',SUM(role='assistant')) FROM chat_message WHERE conversation_uuid='${uuid}'`)[0]
  return { run, steps, count, hasFinal: sse.includes('event:final'), hasError: sse.includes('event:error'), sse }
}

test('预算为零时指定订单问题真实分类并进入订单工具，核验身份后读取真实订单状态', async ({ page }, info) => {
  test.setTimeout(180_000)
  const uuid = await start(page, process.env.OMNI_WIDGET_SUCCESS_URL || 'http://127.0.0.1:15173/widget')
  const first = await send(page, uuid, '给我查询订单 #1001 的情况')
  expect(first.run.intent).toBe('ORDER_STATUS')
  expect(first.run.status).toBe('SUCCESS')
  expect(first.hasFinal).toBe(true)
  expect(first.hasError).toBe(false)
  expect(first.count).toEqual({ user: 1, assistant: 1 })
  const router = first.steps.find(s => s.type === 'ROUTER')
  expect(JSON.parse(router.metadata).specialist).toBe('order')
  expect(JSON.parse(router.metadata).toolAllowlist).toEqual(['queryOrder'])
  // 仅使用项目演示订单的测试邮箱完成现有归属核验，不绕过身份保护。
  const fixture = query("SELECT JSON_OBJECT('email',customer_email,'status',order_status) FROM order_info WHERE tenant_id=1001 AND external_order_number='#1001' AND sync_source='DEMO'")[0]
  expect(fixture.email).toMatch(/@example\.com$/)
  const verified = await send(page, uuid, `请继续查询订单 #1001 的情况，下单邮箱是 ${fixture.email}`)
  expect(verified.run.status).toBe('SUCCESS')
  expect(verified.run.intent).toBe('ORDER_STATUS')
  expect(verified.hasFinal).toBe(true)
  expect(verified.hasError).toBe(false)
  expect(verified.count).toEqual({ user: 2, assistant: 2 })
  expect(verified.steps.filter(s => s.type === 'TOOL').map(s => s.name)).toContain('queryOrder')
  const tool = query(`SELECT JSON_OBJECT('result',result) FROM tool_call_log WHERE trace_id='${verified.run.traceId}' AND tool_name='queryOrder' ORDER BY id DESC LIMIT 1`)[0]
  const result = typeof tool.result === 'string' ? JSON.parse(tool.result) : tool.result
  expect(result.verified).toBe(true)
  expect(result.status).toBe(fixture.status)
  writeFileSync(info.outputPath('order-query-evidence.json'), JSON.stringify({
    conversationUuid: uuid, first: { ...first, sse: undefined }, verified: { ...verified, sse: undefined },
    orderVerified: result.verified, orderStatus: result.status,
  }, null, 2))
  console.log(JSON.stringify({ scenario: '零预算真实订单查询', intent: first.run.intent, traceId: first.run.traceId, verifiedTraceId: verified.run.traceId, orderStatus: result.status }))
})

test('真实预算拒绝保留 BUDGET_EXCEEDED，页面显示额度错误且不执行工具或保存虚假回复', async ({ page }, info) => {
  test.setTimeout(90_000)
  const uuid = await start(page, process.env.OMNI_WIDGET_FAILURE_URL || 'http://127.0.0.1:15174/widget')
  const failed = await send(page, uuid, '给我查询订单 #1001 的情况')
  expect(failed.run.status).toBe('FAILED')
  expect(failed.run.intent).toBeNull()
  expect(failed.run.failureCategory).toBe('BUDGET_EXCEEDED')
  expect(failed.hasFinal).toBe(false)
  expect(failed.hasError).toBe(true)
  expect(failed.sse).toContain('BUDGET_EXCEEDED')
  expect(failed.sse).not.toContain('INTENT_UNSUPPORTED')
  expect(failed.steps.map(s => s.type)).toEqual(['INTENT_CLASSIFICATION', 'FAILURE'])
  expect(failed.steps.map(s => s.category)).toEqual(['BUDGET_EXCEEDED', 'BUDGET_EXCEEDED'])
  expect(failed.count).toEqual({ user: 1, assistant: 0 })
  await expect(page.getByText(/本月模型用量额度已用完/)).toBeVisible()
  writeFileSync(info.outputPath('budget-failure-evidence.json'), JSON.stringify({ conversationUuid: uuid, ...failed, sse: undefined }, null, 2))
  console.log(JSON.stringify({ scenario: '真实额度拒绝', traceId: failed.run.traceId, failureCategory: failed.run.failureCategory, toolCount: 0, assistantCount: 0 }))
})
