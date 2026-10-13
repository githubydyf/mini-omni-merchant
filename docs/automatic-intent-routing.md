# Widget 自动意图识别实施与联调记录

实施日期：2026-10-12（北京时间）。修改项目为 `D:\code\java_code\mini-omni-merchant`，参考项目只读。

买家直接输入中文，服务端自动识别 Intent，继续复用原有 Specialist、Tool Guard、ReAct、RAG、记忆和状态机。

## 实现

- `WidgetView.vue` 删除咨询类型下拉框、响应式 Intent 和请求中的 Intent 字段。
- `WidgetController` 校验令牌与会话后，先保存一次用户消息并同步记忆，再分类、更新会话意图并启动原有 SSE 链。
- `IntentClassificationService` 使用 `ModelRouter` 返回的受限流 DeepSeek，通过 `LlmResilienceService` 调用，不注册业务工具；Spring AI `BeanOutputConverter` 将完整模型输出解析为枚举结构，拒绝未定义意图、工具名称、枚举序号、额外字段及非法 JSON。
- 使用最多六条历史消息，每条最多 600 字符，本轮消息最多 2000 字符。历史中的本轮消息移除后单独标注，不重复持久化。历史助手回复只能帮助理解指代，不能作为实时事实或操作授权。
- 未提供经过校准的置信度，因此结果中不填 `confidence`。耗时、模型和状态由服务端记录。
- 分类失败保持 `UNKNOWN`，工具白名单为空，通过中文 SSE 错误提示补充问题或重试；分类模型仍使用原有租户配额、熔断、超时及安全重试保护，另有 30 秒分类总等待上限。
- 同一 AgentRun 记录真实的 `INTENT_CLASSIFICATION` 步骤，不将分类伪装为工具调用。

结构化输出机制同时核对了本地 Spring AI 2.0.0 接口及[官方输出转换器说明](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)。模型输出仍需服务端约束校验，不能将格式提示本身当作保证。

## 安全边界

旧请求的 `intent` 字段仅保留 DTO 兼容性，后端忽略它。租户来自验证后的 Widget JWT，会话仍须通过数据库归属与状态校验。

退款、退货、改址申请仍须经过本轮明确操作要求检查、订单归属核验、Guard 白名单及幂等保护，只创建待人工审批的内部申请。中文操作要求检查采用保守策略；疑问、否定、假设、引用、权限攻击或不明确的追问不会借用历史申请取得新的操作授权，可能需要买家重新说明本轮需求。

退款和改址分别使用相应工具范围。当前没有取消订单工具，`CANCEL_ORDER` 允许查询订单并转人工处理。

真实跨租户联调还发现并修复了原有 ReAct 初始化、消息持久化和 Trace 收尾未绑定租户作用域的问题。现使用已有 `CallScope` 在相应阶段绑定本轮租户，并在结束后恢复线程现场。新增回归测试模拟模型信号线程残留其他租户的情况。

原 Bootstrap 租户隔离测试依赖已不存在的演示主键，已改为创建自己的随机会话，仅按本测试会话的主键和 UUID 清理，不依赖演示数据重置。

## 构建与回归

以下命令均实际执行，最终结果全部通过：

| 命令 | 最终结果 |
|---|---|
| `mvn clean compile -DskipTests` | 编译通过 |
| `mvn -pl omni-merchant-agent -am test` | 通过，Agent 模块 247 个测试，无失败、错误或跳过 |
| `mvn -pl omni-merchant-bootstrap -am test` | 通过，Bootstrap 模块 34 个测试，无失败、错误或跳过，依赖模块测试也通过 |
| 前端 `npm run build` | TypeScript 检查与 Vite 构建通过，仅有包体积提示 |

新增 `IntentClassificationServiceTest`、`WidgetIntentRoutingTest` 覆盖约定中文样例、多轮指代、上下文限长、未配置模型、接口失败、限流、熔断、超时、解析失败、未知 Intent、跨租户、伪造 Intent、政策咨询和权限攻击。单元测试使用桩模型验证流程与保护边界，不能代表真实模型的分类准确率。

## 真实 DeepSeek 与 Widget 联调

使用真实 Widget 页面、Microsoft Edge、DeepSeek `deepseek-flash`、MySQL、Redis 和现有政策 RAG。没有替换模型结果，也没有模拟业务工具。

浏览器用例：`omnimerchant-web/e2e/widget-intent-live.spec.ts`。最终一次运行耗时 57.7 秒，七个场景全部通过。每轮检查实际分类、Specialist、白名单、真实工具步骤、SSE 的 `final`、用户/助手消息以及 AgentRun 的 `SUCCESS`。

| 场景 | 实际 Intent | Specialist | 实际调用的工具 | 新增售后申请 |
|---|---|---|---|---|
| 推荐蓝牙耳机 | PRODUCT_ADVICE | product | searchProductCatalog | 0 |
| 查询订单 #2002 是否发货 | ORDER_STATUS | order | queryOrder | 0 |
| 同会话追问“那它现在到哪里了？” | LOGISTICS | order | trackLogistics、queryOrder | 0 |
| 退货期限咨询 | POLICY_QA | policy_rag | refundPolicyRAG | 0 |
| “我可以申请退款吗？” | POLICY_QA | policy_rag | refundPolicyRAG | 0 |
| 明确提交订单 #2003 的退款申请 | RETURN_REFUND | return | queryOrder、requestRefundOrReplacement | 1，待人工审批 |
| 明确要求转人工客服 | HUMAN_REQUEST | handoff | escalateToHuman | 0 |

最终通过的 Trace ID：

| 场景 | Trace ID | 分类耗时（毫秒） |
|---|---|---|
| 商品推荐 | 35ea19a804204cb7993799dfc796ce75 | 13482 |
| 订单查询 | 5b4ffb02a6354a1da72584a374a607be | 1213 |
| 物流追问 | a9a9888f644041e0b3092fc65c8f5fea | 1158 |
| 退货政策 | 7f3ccadcc65540c8a85c1e5924d6e2f0 | 1073 |
| 退款资格咨询 | d1a8764e055a49a38b2d00641033bdae | 1310 |
| 明确申请退款 | 81b7351253784957a8c42f14042eb8ed | 1202 |
| 转人工客服 | 8a3d7db93fdd444db3381500bd14eb83 | 1038 |

本次新建申请编号为 `ACT-20261012231536-828738A8`，类型 `REFUND`，订单 `#2003`，数据库状态为 `1`，审批原因 `PAYMENT_OR_FULFILLMENT_ACTION_REQUIRES_HUMAN_APPROVAL`。这表示内部申请等待审批，没有执行外部退款。

完整真实 SSE、消息和脱敏步骤证据保存在 `docs/widget-intent-evidence-2026-10-12.json`，页面截图保存在 `docs/widget-intent-2026-10-12.png`。联调产生的会话、消息、申请及轨迹保留在本地数据库，表格只列最终通过的一组记录。

## 复现

已验证入口为 `http://127.0.0.1:15173/widget`，前端代理至新后端 `18080`；店铺编码可用 `OM-1002` 或 `OM-1001`。默认端口入口要使用新逻辑，需要让后端进程加载本次编译产物。

本机默认 `java` 是 Java 17，应使用 Java 21 启动新产物。联调进程使用 `-Djdk.net.unixdomain.tmpdir=D:\code\java_code\mini-omni-merchant` 解决本机临时目录套接字问题，并通过启动参数将 CORS 允许来源设置为 `http://127.0.0.1:15173`。

浏览器用例默认跳过，显式启用后会调用真实模型并写入测试会话、审批申请及人工交接记录。新后端和前端启动后，在 `omnimerchant-web` 执行：

```powershell
$env:OMNI_WIDGET_LIVE = '1'
$env:OMNI_DB_PASSWORD = '<本地测试数据库密码>'
$env:OMNI_FRONTEND_URL = 'http://127.0.0.1:15173'
$env:OMNI_E2E_EXTERNAL_SERVER = '1'
npx playwright test e2e/widget-intent-live.spec.ts --project=desktop --workers=1 --reporter=list
```

用例默认使用已安装的 Edge，可通过 `OMNI_BROWSER_CHANNEL` 切换浏览器；数据库连接可通过 `OMNI_DB_HOST`、`OMNI_DB_USER`、`OMNI_DB_NAME` 和 `OMNI_MYSQL_BIN` 配置。运行时产生自己的证据文件，不依赖上面的历史 Trace ID。
