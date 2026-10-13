import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'

// 真实 Widget、模型、后台和数据库联调；仅创建独立演示订单，不替换接口结果。
test.skip(process.env.OMNI_ADDRESS_REFUND_LIVE !== '1', '未启用地址与本地退款真实联调')
test.use({ channel: 'msedge', trace: 'off', screenshot: 'off', actionTimeout: 15_000 })
function query(sql: string): any[] {
  const raw = execFileSync('mysql', ['--host=127.0.0.1', '--user=root', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '--raw', 'mini-omni-merchant', '-e', sql], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.OMNI_DB_PASSWORD } })
  return raw.trim() ? raw.trim().split(/\r?\n/).map(line => JSON.parse(line)) : []
}
function fixture(number: string) {
  if (!/^#AR\d+[AR]$/.test(number)) throw new Error('测试订单号不合法')
  query(`INSERT INTO order_info(tenant_id,external_order_id,external_order_number,platform,customer_email,customer_name,customer_phone,order_status,payment_status,fulfillment_status,currency,total_amount,placed_at,sync_source) VALUES(1001,'local-${number}','${number}','custom','local-buyer-it@example.com','联调收件人','13800000000','paid','paid','unfulfilled','CNY',100,NOW(),'DEMO')`)
}
async function send(page: Page, text: string) {
  await page.getByPlaceholder('请输入你的问题').fill(text)
  await page.getByRole('button', { name: /发\s*送/ }).click()
  await expect(page.getByText('已连接', { exact: true })).toBeVisible({ timeout: 100_000 })
}
async function decide(page: Page, requestNo: string, button: string) {
  const row = page.locator('tbody tr').filter({ hasText: requestNo }).first()
  await row.getByRole('button', { name: button, exact: true }).click()
  await page.locator('.ant-popover:visible').getByRole('button', { name: /确\s*定|Yes/ }).click()
  return row
}
function state(number: string) {
  return query(`SELECT JSON_OBJECT('order',order_status,'payment',payment_status,'amount',refunded_amount,'zip',shipping_zip,'version',version) FROM order_info WHERE tenant_id=1001 AND external_order_number='${number}'`)[0]
}
test('中文地址确认审批执行与全额本地模拟退款闭环', async ({ page, browser }, info) => {
  test.setTimeout(360_000)
  const marker = Date.now(), address = `#AR${marker}A`, refund = `#AR${marker}R`
  fixture(address); fixture(refund)
  const adminContext = await browser.newContext({ baseURL: process.env.OMNI_FRONTEND_URL || 'http://127.0.0.1:15173' })
  const admin = await adminContext.newPage()
  try {
    await admin.goto('/login')
    await admin.getByPlaceholder('name@company.com').fill(process.env.OMNI_LIVE_SUPERVISOR_EMAIL!)
    await admin.getByPlaceholder('请输入密码').fill(process.env.OMNI_LIVE_SUPERVISOR_PASSWORD!)
    await admin.getByRole('button', { name: '登录', exact: true }).click()
    await expect(admin).toHaveURL(/\/admin$/)
    await page.goto('/widget')
    await page.getByPlaceholder('店铺编码').fill('OM-1001')
    await page.getByPlaceholder('下单邮箱（可选）').fill('local-buyer-it@example.com')
    await page.getByRole('button', { name: '开始咨询', exact: true }).click()
    await expect(page.getByText('已连接', { exact: true })).toBeVisible()
    await send(page, `请把订单${address}的收货地址修改为广东省广州市天河区中山大道2号，邮编520000，下单邮箱local-buyer-it@example.com。`)
    await expect(page.getByRole('button', { name: '确认地址并提交审批', exact: true })).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.address-confirmation')).toContainText('广东省广州市天河区')
    await expect(page.locator('.address-confirmation')).toContainText('中山大道2号')
    expect(query(`SELECT JSON_OBJECT('count',COUNT(*)) FROM return_request WHERE tenant_id=1001 AND external_order_number='${address}'`)[0].count).toBe(0)
    const confirmResponse = page.waitForResponse(r => r.url().endsWith('/api/widget/address-confirmation') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '确认地址并提交审批', exact: true }).click()
    const confirmation = (await (await confirmResponse).json()).data
    expect(confirmation.status).toBe('PENDING_HUMAN_APPROVAL')
    await expect(page.locator('.address-confirmation')).toHaveCount(0)
    const savedAddress = query(`SELECT JSON_OBJECT('structured',JSON_TYPE(JSON_EXTRACT(requested_items,'$.newAddress')),'confirmed',JSON_EXTRACT(requested_items,'$.customerConfirmed')) FROM return_request WHERE tenant_id=1001 AND request_no='${confirmation.requestNo}'`)[0]
    expect(savedAddress.structured).toBe('OBJECT'); expect(savedAddress.confirmed).toBe(true)
    await admin.goto('/admin/actions')
    await decide(admin, confirmation.requestNo, '批准申请')
    expect(state(address).zip).toBe(null)
    await decide(admin, confirmation.requestNo, '执行已批准操作')
    await expect(admin.locator('tbody tr').filter({ hasText: confirmation.requestNo })).toContainText('本地收货地址已更新')
    expect(state(address)).toMatchObject({ order: 'paid', payment: 'paid', zip: '520000', version: 1 })
    console.log('地址链路已通过：中文提取、客户确认、审批与本地执行')

    await send(page, `请为订单${refund}申请全额退款，原因是不再需要，下单邮箱local-buyer-it@example.com。`)
    const requests = query(`SELECT JSON_OBJECT('id',id,'no',request_no,'type',request_type,'status',status) FROM return_request WHERE tenant_id=1001 AND external_order_number='${refund}'`)
    expect(requests).toHaveLength(1); expect(requests[0]).toMatchObject({ type: 'REFUND', status: 1 })
    await admin.reload()
    await decide(admin, requests[0].no, '批准申请')
    await expect(admin.locator('tbody tr').filter({ hasText: requests[0].no })).toContainText('已批准，待模拟退款')
    expect(state(refund)).toMatchObject({ order: 'paid', payment: 'paid', amount: 0 })
    await decide(admin, requests[0].no, '执行本地模拟退款')
    await expect(admin.locator('tbody tr').filter({ hasText: requests[0].no })).toContainText('本地模拟退款已完成')
    await expect(admin.locator('tbody tr').filter({ hasText: requests[0].no })).toContainText('未调用真实支付渠道')
    expect(state(refund)).toMatchObject({ order: 'refunded', payment: 'refunded', amount: 100, version: 1 })
    const record = query(`SELECT JSON_OBJECT('mode',execution_mode,'state',state,'actualConfirmed',payment_refund_confirmed,'actor',actor_id) FROM commerce_action_record WHERE tenant_id=1001 AND source='return_request' AND request_id=${requests[0].id} AND execution_key='LOCAL'`)
    expect(record).toHaveLength(1); expect(record[0]).toMatchObject({ mode: 'LOCAL_SIMULATION', state: 'EXECUTED', actualConfirmed: 0 })
    await admin.goto('/admin/orders')
    await admin.getByPlaceholder(/搜索订单/).fill(refund)
    await admin.getByRole('button', { name: /查\s*询/, exact: true }).click()
    await expect(admin.locator('tbody tr').filter({ hasText: refund })).toContainText('本地模拟已退款')
    await expect(admin.locator('tbody tr').filter({ hasText: refund })).toContainText('实际支付退款未确认')
    const count = await page.locator('.msg.assistant').count()
    await send(page, `请查询订单${refund}最新退款进度，下单邮箱local-buyer-it@example.com。`)
    await expect(page.locator('.msg.assistant')).toHaveCount(count + 1)
    const reply = await page.locator('.msg.assistant').last().innerText()
    expect(reply).toContain('模拟')
    expect(reply).toMatch(/未确认|未.*支付|没有.*支付|不代表.*到账|尚未.*到账|不.*实际/)
    const evidence = { addressOrder: address, refundOrder: refund, addressState: state(address), refundState: state(refund), addressCustomerConfirmed: true, addressPayloadStructured: true, refundApprovalSeparateFromExecution: true, refundRecord: record[0], widgetReplyMentionsSimulation: reply.includes('模拟'), widgetActualPaymentNotConfirmed: true }
    writeFileSync(info.outputPath('address-refund-evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(JSON.stringify(evidence))
  } finally { await adminContext.close() }
})
