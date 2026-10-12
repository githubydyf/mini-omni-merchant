# OmniMerchant 中文演示环境初始化说明（阶段 12）

本说明用于**全中文演示环境**的数据初始化与重置。全部演示业务数据使用简体中文；
数据库中的程序标识符（`ORDER_STATUS`、`POLICY_QA`、`WEB`、`WEB_WIDGET`、`SUCCESS` 等）保持原样，不做翻译。

> 安全总则：**默认只预览（dry-run），不删除**。数据清理/覆盖必须由你显式加 `-Apply` 才会执行；
> 涉及覆盖旧演示数据的操作，务必先看预览输出再决定。

---

## 1. 演示租户与商品设计

| 租户 ID | tenant_code | 店铺名称 | 行业 | 主要商品 |
|---|---|---|---|---|
| 1001 | `OM-1001` | 星途户外用品店 | 户外运动 | 防水旅行背包 28L、轻量冲锋衣、轻便徒步鞋、不锈钢保温杯、便携露营灯、户外遮阳帽、旅行收纳包、运动水壶 |
| 1002 | `OM-1002` | 智选数码生活馆 | 数码科技 | 主动降噪蓝牙耳机、机械键盘 87 键、无线鼠标、移动电源 20000mAh、65W 氮化镓充电器、智能手表、便携蓝牙音箱、USB-C 扩展坞 |

- 两家店铺均 `status=1`（启用）、`default_lang=zh`、`support_langs=["zh"]`、币种 `CNY`（人民币）。
- `platform='custom'`：当前项目**尚未接入** Shopify / 企业微信 / 抖店，不使用这些平台标识。
- 商品覆盖：正常库存 / 低库存 / 无库存；正常上架 / 已下架。

## 2. 数据规模（本次演示数据集）

| 实体 | 租户 1001 | 租户 1002 | 说明 |
|---|---|---|---|
| 客户 | 6 | 6 | 中文姓名，`@example.com` 测试邮箱 |
| 商品 | 8 | 8 | 中文标题/描述，SKU 唯一 |
| 订单 | 10 | 10 | 覆盖 10 类售后场景，真实关联客户与商品 SKU |
| 会话 | 6 | 6 | 场景：订单/物流/商品推荐/政策/退款/人工升级/质量 |
| 消息 | 14 | 13 | 中文；**静态演示样例**，非真实模型输出 |
| 升级记录 / 工单 | 2 | 3 | 会话 → 升级 → 工单 真实关联 |
| 售后申请(ReturnRequest) | 4 | 4 | 关联真实订单号 |
| 动作审批(ActionRequest) | 2 | 2 | 关联真实订单与操作人 |

## 3. 目录与文件

```
sql/demo/
  00_check_schema.sql            # 只读：现状盘点（不改数据）
  01_seed_tenant_identity.sql    # 租户 + 角色权限 + 演示身份（幂等 UPSERT）
  02_seed_customer_product.sql   # 中文客户 + 商品
  03_seed_orders.sql             # 订单 + 回填客户消费汇总
  04_seed_conversations.sql      # 会话 + 消息 + 回填 message_count/lastMessageAt
  05_seed_after_sales.sql        # 升级 / 工单 / 售后审批
  06_verify_relations.sql        # 只读：关联与跨租户一致性校验（15+ 项）
scripts/
  reset-demo-data.ps1            # 初始化/重置脚本（默认 dry-run）
backup/                          # 重置前逻辑备份目录（自动创建）
```

## 4. 执行步骤

### 4.1 先看现状（只读，随时可跑）
```bash
mysql -uroot -p -h127.0.0.1 -P3306 mini-omni-merchant -t < sql/demo/00_check_schema.sql
```

### 4.2 预览将清理/写入的范围（不修改任何数据）
```powershell
pwsh scripts/reset-demo-data.ps1
```
输出：各演示标记的行数统计 + "运行期残留（不会被自动删除）"清单。

### 4.3 首次初始化（只灌种子，不清理）
```powershell
pwsh scripts/reset-demo-data.ps1 -Apply -SeedOnly
```
或手动按顺序执行：
```bash
mysql ... mini-omni-merchant < sql/demo/01_seed_tenant_identity.sql
mysql ... mini-omni-merchant < sql/demo/02_seed_customer_product.sql
mysql ... mini-omni-merchant < sql/demo/03_seed_orders.sql
mysql ... mini-omni-merchant < sql/demo/04_seed_conversations.sql
mysql ... mini-omni-merchant < sql/demo/05_seed_after_sales.sql
mysql ... mini-omni-merchant -t < sql/demo/06_verify_relations.sql
```

### 4.4 重置（备份 → 清理演示数据 → 重建 → 校验）
```powershell
pwsh scripts/reset-demo-data.ps1 -Apply
```
- 自动 `mysqldump` 备份到 `backup/mini-omni-merchant-<时间戳>.sql`（可用 `-SkipBackup` 关闭，不推荐）。
- 只删除带演示标记的数据；**运行期残留（随机 UUID 会话/消息、`it-*` 测试账号）不在删除范围**。
- 结束自动执行 `06_verify_relations.sql`，仅当无 `FAIL` 才报告成功。

环境变量：`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD`（也可用参数覆盖）。

## 5. 演示后台账号（重要）

演示身份（`app_user` id 9001-9007）默认 **DISABLED**，**无法登录**——这是有意设计，
避免把可用的固定演示密码写进 SQL / Git。

| id | 邮箱 | 角色 | 所属租户 |
|---|---|---|---|
| 9001 | demo-admin@example.com | 平台管理员 | — |
| 9002/9003/9004 | demo-1001-{admin,supervisor,agent}@example.com | 租户管理员/主管/客服 | 1001 |
| 9005/9006/9007 | demo-1002-{admin,supervisor,agent}@example.com | 租户管理员/主管/客服 | 1002 |

### 启用可登录账号（任选其一）

**方式 A（推荐）：用平台管理员在后台创建/重置**
1. 先用环境变量引导一个平台管理员（`IdentityService` 在首次启动时创建）：
   ```
   ADMIN_EMAIL=you@example.com  ADMIN_PASSWORD=<至少12位强密码>  java -jar omni-merchant-bootstrap-...jar
   ```
2. 登录后台 `/api/auth/login` → 调 `/api/admin/users` 创建用户并设置 membership；
   或调 `/api/admin/users/{id}/memberships` 给上面的 demo 用户分配角色。

**方式 B：用应用生成 BCrypt 后 UPDATE**
```bash
# 在应用里生成哈希（例如用 PasswordEncoder），再：
# UPDATE app_user SET password_hash='<bcrypt>', status='ACTIVE' WHERE id=9004;
```
> 禁止把真实可用的演示密码直接提交到 `sql/demo/*.sql` 或 Git。

## 6. 数据重建安全规则（脚本已实现）

1. **默认 dry-run**：不加 `-Apply` 只统计、不删除。
2. **明确确认**：只有 `-Apply` 才执行清理/覆盖。
3. **拒绝生产库**：库名含 `prod` 直接中止；并校验当前连接库名与参数一致、演示租户 1001/1002 存在。
4. **先备份**：重置前 `mysqldump --single-transaction --routines`。
5. **只删演示数据**：按 `demo-zh-*` / `DEMO-ZH-*` / `cus_*` / `prod_*` / `ord_*` / `sync_source='DEMO'` / id 区间等标记收敛范围。
6. **不触碰无法识别的数据**：运行期随机 UUID 会话/消息、测试账号不会被自动删除。
7. **子表→父表删除顺序**，遵守外键。
8. **失败即报错**，并提示首次初始化请用 `-SeedOnly`。

## 7. 关联与隔离校验（06）

`06_verify_relations.sql` 真实执行以下检查（违规数必须为 0）：

1. 订单引用的客户存在
2. 订单与客户同租户
3. 订单商品 SKU 属于对应租户商品库
4. 会话引用的客户存在
5. 会话引用的订单号存在
6. ChatMessage 引用的会话存在
7. ChatMessage 与会话 tenantId 一致
8. 同一会话 seqNo 不重复
9. 会话 message_count == 真实消息数
10. 升级记录关联真实会话（且同租户）
11. 工单关联真实升级记录
12. 售后申请关联真实订单号
13. 审批操作人引用真实用户
14. 不存在跨租户业务引用
15. 订单状态无矛盾（cancelled 不得 paid；refunded 必须 payment_status=refunded）
16. 客户 total_orders 与真实订单数一致

## 8. 全链路验收（真实验证）

初始化后按以下顺序联调（详见任务书"二十、全链路验收"）：

1. 启动后端 + 前端，打开 `/widget`，用店铺编码 `OM-1001` / `OM-1002` 创建会话。
2. 依次验证：商品咨询→ProductTools；订单查询→OrderTools；物流→LogisticsTools；
   退货政策→PolicyTools(RAG)；退款申请→ReturnRequest(待审批)；转人工→Escalation→Ticket。
3. 后台 `/admin/inbox`、`/admin/tickets`、`/admin/actions` 查看真实会话、工单与审批。
4. 同一会话连续提问，验证 RedisChatMemory 上下文。
5. 重复提交退款，验证 AgentIdempotencyGuard 返回 DUPLICATE_BLOCKED。
6. 租户 1001 / 1002 分别登录，验证只能看到各自数据，跨租户访问被拒绝。

> 说明：SQL 中的客服消息是**静态演示样例**，不代表真实 DeepSeek 调用结果。
> 真实 AI 回答请通过 `/widget` 实际对话生成；真实 Trace（AgentRun/AgentStep/ToolCallLog）
> 也只应通过真实请求产生，本次不批量伪造。

## 9. 本阶段不做

多语言/翻译、Shopify、企业微信、抖店、RocketMQ、ChannelAdapter、复杂 Widget SDK、
自动计费、新 Agent/RAG 框架。只完成：中文统一、演示数据重建、关联校验、全链路验收。
