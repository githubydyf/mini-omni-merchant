# Widget 分类失败与额度异常排查及修复

排查日期：2026-10-13，北京时间。只修改当前项目；原项目只读。

## 排查开始时的真实证据

最新失败 Trace 为 `c1f90186e66545b19a3de85da9364d61`，时间为 10:17:05，租户为 1001。

分类步骤记录 `BUDGET_EXCEEDED`，分类耗时约 30 毫秒；随后 AgentRun 被错误归类为 `INTENT_UNSUPPORTED`。这次分类在 Redis 准入检查时已被拒绝，没有调用 DeepSeek。`UNKNOWN` 来自异常兜底，非模型判断。

当时存在两个后端进程。直接读取运行中 Spring Bean，而非仅检查 YAML，得到：

| 项目 | 页面使用的 18080 进程 | IDEA 的 8080 进程 |
|---|---|---|
| PID | 54632 | 24240 |
| 月度预算 | 100000 | 0 |
| QPS / 并发 | 5 / 3 | 5 / 3 |
| 模型 | deepseek-flash | deepseek-flash |
| 模型 Bean / API Key | 存在 / 已加载，仅检查布尔值 | 存在 / 已加载，仅检查布尔值 |
| 熔断器 | CLOSED | CLOSED |
| Profile | 默认，无显式 Profile | 默认，无显式 Profile |
| 预算配置来源 | 临时 JAR 内的 application.yml | 当前 classpath 的 application.yml |

15173 前端的实际代理目标为 18080。该进程仍运行前一天复制到临时目录的 JAR，里面的月度预算仍为 100000。当前源码 YAML 已是 0；修改它不会更新另一个运行中的 JAR 进程。

排查时 Redis 当前月份为 `202610`：

| 租户 | 月度实际用量 | TTL（秒） |
|---|---|---|
| 1001 | 99209 | 2679353 |
| 1002 | 67638 | 2724032 |

每次模型请求准入估算为 1000 Token，`99209 + 1000 > 100000`，因此旧进程拒绝请求。Redis 中的数值是用量，不是配置上限。

运行中的旧 Lua SHA1 为 `78c8e440f88fe907e2996d220445a8f88e92528b`，与当时本地资源匹配。Java 参数顺序正确；Lua 仅在 `monthly_budget > 0` 时检查预算。预估值只参与准入检查，不会累计到预算键；累计值由模型返回的真实 Usage 增加。本次没有发现脚本逻辑或月份错配。

## 确认的根因

1. 当前页面连接的旧 JAR 没有加载源码中的预算 0，继续按 100000 限额拒绝请求。
2. 分类服务捕获所有调用异常后返回 `UNKNOWN / FAILED`；Controller 无条件更新意图并启动 Agent；ReAct 看到空白名单后再标记 `INTENT_UNSUPPORTED`。真实配额故障被错误解释为用户需求不明确。

没有修改分类 Prompt，也没有通过清空 Redis 掩盖问题。

## 最小修复与修改文件

| 文件 | 修改 |
|---|---|
| IntentClassificationService.java | 增加明确的 failureCategory / failureReason；调用失败的 intent 为 null，仅真实不确定结果为 UNKNOWN / UNCERTAIN |
| FailureAttributionService.java | 保留具体限流码，识别包装后的超时、熔断及解析异常 |
| WidgetController.java | 用户消息照常保存与同步；分类失败不更新会话意图、不调用 Agent，直接记录失败 Trace 并返回 SSE error |
| ChatSseSupport.java | 复用现有转发逻辑发送分类失败 error，不发送虚假 final |
| AgentTraceService.java | 分类步骤使用服务端明确提供的失败类别，与 AgentRun 失败类别保持一致 |
| ReActAgentService.java | 复用分类轨迹记录；真实不确定结果记为 INTENT_UNCERTAIN，保留原有不支持意图处理 |
| TokenRateLimiter.java | 月度预算默认值改为 0；启动日志记录真实 QPS、月度预算、并发与脚本 SHA1；拒绝日志记录预算值、估算和月份 |
| RateLimitedChatModel.java、rate_limit.lua | 仅修正估算/真实累计用量的注释，并补全 Lua 第九个参数说明，未重写限流逻辑 |

`application.yml` 中预算 0 是本次开始前已有的用户修改，本次保留该值。`application-prod.yml` 没有覆盖限流设置。QPS、并发和熔断保护保持生效。

补充或修改测试：`IntentClassificationServiceTest`、`WidgetIntentRoutingTest`、`FailureAttributionServiceTest`、`AgentTraceServiceTest`、`TokenRateLimiterTest`，新增真实 `TokenRateLimiterRedisTest` 和浏览器用例 `widget-budget-fix-live.spec.ts`。

## 修改前后行为

- 调用失败：原先 `BUDGET_EXCEEDED → UNKNOWN → INTENT_UNSUPPORTED → 提示补充问题`；现在保留 `BUDGET_EXCEEDED`，Trace 与 SSE 明确提示额度错误。
- 模型不可用、超时、熔断、JSON 解析失败：分别记录 `MODEL_UNAVAILABLE`、`LLM_TIMEOUT`、`CIRCUIT_OPEN`、`CLASSIFICATION_PARSE_ERROR`。
- 模型确实无法判断：仍为 `UNKNOWN / UNCERTAIN`，进入空工具白名单的安全澄清流程。
- 已保存的用户消息保留；调用失败不保存助手消息、不执行订单工具、不伪造成功回复。

新日志仅记录租户、会话、Trace、状态、失败类别、模型、耗时及配额诊断字段，不增加完整用户消息、客户联系信息或密钥日志。真实联调进程关闭了 SQL 参数明文日志；证据文件不保存 SSE 原文、JWT 或测试邮箱。

## 最终验证

三项要求的命令均实际执行并最终通过：

| 命令 | 结果 |
|---|---|
| mvn clean compile -DskipTests | 编译通过 |
| mvn -pl omni-merchant-agent -am test | Agent 模块 268 项通过，无失败、错误或跳过 |
| mvn -pl omni-merchant-bootstrap -am test | Bootstrap 模块 34 项通过，无失败、错误或跳过；依赖模块也通过 |

真实 Redis 测试四项全部通过：0 额度允许高额旧用量、正额度拒绝超限、QPS 有效、并发限制有效。使用独立随机测试键并设置自动过期，未清空 Redis 或删除业务预算键。

本机测试 JVM 存在 Windows 临时目录套接字问题。测试进程临时使用 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\code\java_code\mini-omni-merchant`，结束后恢复原环境值；未修改操作系统配置。

重建并替换了页面实际连接的 18080 进程。新 PID 为 60032，启动日志及直接读取 Bean 均确认：

```text
monthlyTokenBudget=0
qpsLimit=5
concurrentLimit=3
model=deepseek-flash
modelBeanPresent=true
apiKeyPresent=true
circuitState=CLOSED
```

新脚本 SHA1 为 `75abfcbb7ce6bf7b00d5bd404ff301fb8526cd13`；变化仅来自注释，限流判断保持原样。

真实 Widget / Edge / DeepSeek 联调两项通过：

| 场景 | 结果 | Trace ID |
|---|---|---|
| 输入“给我查询订单 #1001 的情况” | ORDER_STATUS、order Specialist、允许 queryOrder、中文 final | 2701b39ac7494d98a14b217321c50b77 |
| 同会话补充演示下单邮箱 | 实际调用 queryOrder，verified=true，状态 paid，与数据库一致 | 4d56f130fc444059ab76468de5da0f53 |
| 专用负向进程预算设为 1 | BUDGET_EXCEEDED，两个失败步骤均保留此类别，无工具、无助手消息、无 final，页面显示额度错误 | 6be332aaffd94f0685b8bb9e0ae60432 |

首次未提供邮箱时，模型可能先要求订单归属核验；核验后才能返回详细订单信息。本次没有放宽这项现有保护。

验证结束后，1001 月度用量为 115135、TTL 为 2676934 秒，已超过旧的 100000 上限，新进程仍正常处理；1002 用量保持 67638。证明预算 0 不受旧累计用量影响。

负向测试使用独立 18081 后端及 15174 前端，仅通过该进程启动参数设置预算 1，未修改正式配置。测试后这两个临时进程已关闭。当前页面仍可使用 `http://127.0.0.1:15173/widget`，连接预算 0 的新 18080 后端。

完整脱敏证据保存在 `docs/widget-budget-fix-evidence-2026-10-13.json`。

## 验证范围

当前页面、真实 Redis、运行配置、DeepSeek 分类、订单工具、SSE、消息持久化和真实错误归因均已验证。

没有对正式提供商主动制造宕机、熔断、超时或非法 JSON；这些错误分支由模型桩、归因和 SSE 回归测试覆盖。没有重启 IDEA 的 8080 调试进程，也没有在该旧进程上联调新错误处理代码；如改用 IDEA 入口，需要重启以加载本次编译产物。

仅完成本次 Bug 修复，未开发新功能。
