import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'

// 显式启用真实服务、真实数据库和真实模型；不替换接口返回，不记录令牌或联系信息。
test.skip(process.env.OMNI_HUMAN_ACTIONS_LIVE !== '1', '未启用人工消息与本地执行真实联调')
test.use({ channel: 'msedge', trace: 'off', screenshot: 'off', actionTimeout: 15_000 })
function query(sql: string): any[] {
  const raw = execFileSync('mysql', ['--host=127.0.0.1', '--user=root', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '--raw', 'mini-omni-merchant', '-e', sql],
    { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.OMNI_DB_PASSWORD } })
  return raw.trim() ? raw.trim().split(/\r?\n/).map(line => JSON.parse(line)) : []
}
function fixtureOrder(number: string) {
  if (!/^#E2E\d+[A-Z]$/.test(number)) throw new Error('测试订单号格式不合法')
  query(`INSERT INTO order_info(tenant_id,external_order_id,external_order_number,platform,customer_email,customer_name,order_status,payment_status,fulfillment_status,currency,total_amount,placed_at,sync_source) VALUES(1001,'local-${number}','${number}','custom','local-buyer-it@example.com','闭环测试买家','paid','paid','unfulfilled','CNY',100,NOW(),'DEMO')`)
}
async function login(page: Page) {
  if (!process.env.OMNI_LIVE_SUPERVISOR_EMAIL || !process.env.OMNI_LIVE_SUPERVISOR_PASSWORD) throw new Error('缺少联调主管账号')
  await page.goto('/login')
  await page.getByPlaceholder('name@company.com').fill(process.env.OMNI_LIVE_SUPERVISOR_EMAIL)
  await page.getByPlaceholder('请输入密码').fill(process.env.OMNI_LIVE_SUPERVISOR_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/admin$/)
}
async function sendWidget(page: Page, text: string) {
  await page.getByPlaceholder('请输入你的问题').fill(text)
  await page.getByRole('button', { name: /发\s*送/ }).click()
  await expect(page.getByText('已连接', { exact: true })).toBeVisible({ timeout: 90_000 })
}
async function reply(page: Page, text: string) {
  await page.getByPlaceholder('输入给客户的回复').fill(text)
  await page.getByRole('button', { name: '发送回复' }).click()
  await expect(page.locator('.message-bubble').filter({ hasText: text })).toBeVisible()
}
async function decideRow(page: Page, number: string, button: string) {
  const row = page.locator('tbody tr').filter({ hasText: number }).first()
  await row.getByRole('button', { name: button }).click()
  await page.locator('.ant-popover:visible').getByRole('button', { name: /确\s*定|Yes/ }).click()
  return row
}

test('真实双页面人工沟通、断网补齐、同文消息去重、审批与本地执行', async ({ page, browser }, info) => {
  test.setTimeout(300_000)
  const marker = String(Date.now()), buyerName = `闭环联调${marker}`
  const refundOrder = `#E2E${marker}R`, cancelOrder = `#E2E${marker}C`, addressOrder = `#E2E${marker}A`
  for (const number of [refundOrder, cancelOrder, addressOrder]) fixtureOrder(number)
  const adminContext = await browser.newContext({ baseURL: process.env.OMNI_FRONTEND_URL || 'http://127.0.0.1:15173' })
  const admin = await adminContext.newPage()
  try {
    await login(admin)
    await page.goto('/widget')
    await page.getByPlaceholder('店铺编码').fill('OM-1001')
    await page.getByPlaceholder('你的称呼（可选）').fill(buyerName)
    const created = page.waitForResponse(r => r.url().endsWith('/api/widget/session'))
    await page.getByRole('button', { name: '开始咨询' }).click()
    const session = (await (await created).json()).data
    const uuid = session.conversationUuid
    expect(uuid).toMatch(/^[a-f0-9-]{36}$/)
    await expect(page.getByText('已连接', { exact: true })).toBeVisible()
    await sendWidget(page, `我要申请订单 ${refundOrder} 退款，下单邮箱是 local-buyer-it@example.com，原因是买错商品。请提交退款申请。`)
    await expect(page.locator('.msg.assistant .message-source').filter({ hasText: 'AI 客服' })).toHaveCount(1)
    const requests = query(`SELECT JSON_OBJECT('id',id,'requestNo',request_no,'status',status) FROM return_request WHERE tenant_id=1001 AND external_order_number='${refundOrder}' AND request_type='REFUND' ORDER BY id DESC LIMIT 1`)
    expect(requests).toHaveLength(1)
    expect(requests[0].status).toBe(1)
    const baseline = query(`SELECT JSON_OBJECT('runs',COUNT(*)) FROM agent_run WHERE conversation_uuid='${uuid}'`)[0].runs
    console.log('联调进度：真实退款申请已创建，进入 Inbox');
    await admin.goto('/admin/inbox'); console.log('Inbox 队列：' + await admin.locator('.ant-radio-group').ariaSnapshot())
    await admin.locator('.ant-radio-button-wrapper').filter({ hasText: 'AI 处理中' }).click()
    console.log('联调进度：已选择 AI 队列');
    await admin.locator('.conversation-item').filter({ hasText: buyerName }).click(); console.log('联调进度：已选择买家会话')
    await expect(admin.locator('.message-bubble').filter({ hasText: refundOrder }).first()).toBeVisible()
    await admin.getByRole('button', { name: /接\s*管/, exact: true }).click()
    await expect(admin.getByRole('button', { name: /接\s*管/, exact: true })).toHaveCount(0)
    await reply(admin, '您好，我是人工客服，正在处理这笔售后申请。')
    await expect(page.locator('.msg.assistant').filter({ hasText: '您好，我是人工客服，正在处理这笔售后申请。' })).toHaveCount(1)
    await expect(page.locator('.msg.assistant').filter({ hasText: '正在处理这笔售后申请' }).getByText('人工客服', { exact: true })).toBeVisible()
    await expect(page.locator('.msg.assistant .message-source').filter({ hasText: 'AI 客服' })).toHaveCount(1)
    await sendWidget(page, '相同的补充内容')
    await sendWidget(page, '相同的补充内容')
    await expect(page.locator('.msg.user').filter({ hasText: '相同的补充内容' })).toHaveCount(2)
    await expect(admin.locator('.message-bubble').filter({ hasText: '相同的补充内容' })).toHaveCount(2)
    expect(query(`SELECT JSON_OBJECT('runs',COUNT(*)) FROM agent_run WHERE conversation_uuid='${uuid}'`)[0].runs).toBe(baseline)
    await page.context().setOffline(true)
    await expect(page.getByText('消息同步暂时中断，连接恢复后将自动补齐')).toBeVisible({ timeout: 9000 })
    await reply(admin, '断网期间的人工回复，恢复连接后应自动补齐。')
    await page.context().setOffline(false)
    await expect(page.locator('.msg.assistant').filter({ hasText: '断网期间的人工回复' })).toHaveCount(1)
    await page.reload()
    await expect(page.locator('.msg.user').filter({ hasText: '相同的补充内容' })).toHaveCount(2)
    await expect(page.locator('.msg.assistant .message-source').filter({ hasText: 'AI 客服' })).toHaveCount(1)

    await admin.goto('/admin/actions')
    await decideRow(admin, requests[0].requestNo, '批准申请')
    await expect(admin.locator('tbody tr').filter({ hasText: requests[0].requestNo })).toContainText('已批准，等待人工退款处理；未执行支付退款')
    expect(query(`SELECT JSON_OBJECT('payment',payment_status,'order',order_status) FROM order_info WHERE tenant_id=1001 AND external_order_number='${refundOrder}'`)[0]).toEqual({ payment: 'paid', order: 'paid' })
    const executionEvidence: any[] = []
    for (const [number, address] of [[cancelOrder, false], [addressOrder, true]] as const) {
      await admin.getByRole('button', { name: '登记本地订单申请', exact: true }).click()
      const modal = admin.locator('.ant-modal:visible')
      await modal.getByPlaceholder('例如 #1001').fill(number)
      if (address) {
        await modal.locator('.ant-select').click()
        await admin.locator('.ant-select-dropdown:visible').getByText('修改收货地址', { exact: true }).click()
        const values = ['测试收件人', '13800000000', '北京市朝阳区测试路一号', '北京市', '100000']
        const fields = modal.locator('.ant-form-item').filter({ has: admin.locator('input:not([readonly])') })
        for (let i = 0; i < values.length; i++) await fields.nth(i + 1).locator('input').fill(values[i])
      }
      await modal.locator('textarea').fill('客户通过人工客服申请本地订单处理')
      const createdRequest = admin.waitForResponse(r => r.url().endsWith('/api/actions/local-requests') && r.request().method() === 'POST')
      await modal.getByRole('button', { name: /确\s*定/ }).click()
      const data = (await (await createdRequest).json()).data
      expect(data.status).toBe('PENDING_APPROVAL')
      await decideRow(admin, number, '批准申请')
      await expect(admin.locator('tbody tr').filter({ hasText: number })).toContainText('已批准，待本地执行')
      await decideRow(admin, number, '执行已批准操作')
      await expect(admin.locator('tbody tr').filter({ hasText: number })).toContainText(address ? '本地收货地址已更新' : '本地订单已取消')
      const state = query(`SELECT JSON_OBJECT('order',order_status,'payment',payment_status,'zip',shipping_zip,'version',version) FROM order_info WHERE tenant_id=1001 AND external_order_number='${number}'`)[0]
      expect(state.payment).toBe('paid'); expect(state.version).toBe(1)
      expect(state.order).toBe(address ? 'paid' : 'cancelled')
      if (address) expect(state.zip).toBe('100000')
      await admin.getByRole('button', { name: data.requestNo, exact: true }).click()
      await expect(admin.getByText('本地执行成功', { exact: false })).toBeVisible()
      await expect(admin.getByText('审批批准 · 已批准', { exact: true })).toBeVisible()
      await admin.locator('.ant-drawer:visible').getByRole('button', { name: /Close|关闭/ }).click()
      executionEvidence.push({ requestId: data.id, type: data.actionType, ...state })
    }
    const rows = query(`SELECT JSON_OBJECT('id',message_uuid,'seq',seq_no,'source',source) FROM chat_message WHERE conversation_uuid='${uuid}' ORDER BY seq_no`)
    expect(new Set(rows.map(r => r.id)).size).toBe(rows.length)
    expect(new Set(rows.map(r => r.seq)).size).toBe(rows.length)
    writeFileSync(info.outputPath('human-actions-evidence.json'), JSON.stringify({ conversationUuid: uuid, aiRunsBeforeAndAfterHandoff: baseline, messageCount: rows.length, sources: rows.map(r => r.source), refundApprovalWithoutPayment: true, executionEvidence }, null, 2))

    // 令牌前端到期会停止轮询；后端签名过期与跨会话拒绝由真实认证链测试覆盖。
    await page.evaluate(() => { const saved = JSON.parse(sessionStorage.getItem('omni_widget_session')!); saved.expiresAt = new Date(Date.now() + 1000).toISOString(); sessionStorage.setItem('omni_widget_session', JSON.stringify(saved)) })
    await page.reload()
    await expect(page.getByRole('button', { name: '开始咨询', exact: true })).toBeVisible({ timeout: 8000 })
    console.log(JSON.stringify({ scenario: '人工同步与本地执行真实联调', conversationUuid: uuid, messageCount: rows.length, executionEvidence }))
  } finally { await page.context().setOffline(false).catch(() => {}); await adminContext.close().catch(() => {}) }
})
