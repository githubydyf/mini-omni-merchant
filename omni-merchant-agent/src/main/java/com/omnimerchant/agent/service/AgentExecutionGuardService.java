package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.entity.AgentIdempotencyGuard;
import com.omnimerchant.agent.mapper.AgentIdempotencyGuardMapper;
import com.omnimerchant.agent.tool.GuardOutcomeHolder;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Agent 执行守卫：<b>会话互斥锁 + 运行时工具二次校验 + 副作用工具幂等</b>。
 *
 * <p>复现自参考项目 {@code service/AgentExecutionGuardService}，保留三层保护：
 * <ol>
 *   <li><b>Redis 会话锁</b>：同一会话同一时刻只允许一个 Agent Run（{@code SET NX EX} + Lua 释放）；</li>
 *   <li><b>运行时二次校验</b>：{@code GuardedToolCallback} 执行前再次校验 ToolContext 与
 *       allowedTools，而不是只靠 System Prompt 约束；</li>
 *   <li><b>副作用幂等</b>：{@code createReturnRequest / requestRefundOrReplacement /
 *       requestAddressChange / escalateToHuman} 执行前先登记，靠数据库唯一键原子竞争。</li>
 * </ol>
 *
 * <p><b>相对参考项目的必要增强</b>：
 * <ul>
 *   <li>锁 TTL 90 秒 &lt; SSE 超时 300 秒，因此增加<b>定期续期</b>（Lua 校验 token 后刷新 TTL）；
 *       续期失败即视为失去锁，之后不允许再触发新的副作用工具；</li>
 *   <li>幂等键对参数做<b>规范化</b>（字段排序 / 去空格）并优先取稳定业务字段，
 *       既避免字段顺序不同绕过幂等，也避免把邮箱、地址等敏感明文写进键。</li>
 * </ul>
 *
 * <p><b>事务边界</b>：{@code beginSideEffect} 不做"先 SELECT 再 INSERT"，也<b>不</b>使用
 * {@code @Transactional} 自调用（自调用会因代理边界失效）。直接用 MyBatis 单语句自动提交，
 * 既保证登记先于业务执行提交，又不会把远程模型调用包进长事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionGuardService {

    /** 副作用工具：只有这些需要幂等保护。 */
    private static final Set<String> SIDE_EFFECT_TOOLS = Set.of(
            "createReturnRequest", "requestRefundOrReplacement", "requestAddressChange", "escalateToHuman");

    /** 释放锁：仅当 Redis 中的 token 与本次持有的一致才删除（原子 compare-and-delete）。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 续期：仅当持有者 token 一致才刷新 TTL（原子 compare-and-expire）。 */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /** 锁续期调度器（守护线程，避免阻塞应用关闭）。 */
    private static final ScheduledExecutorService LOCK_RENEWER = Executors.newScheduledThreadPool(2, runnable -> {
        var thread = new Thread(runnable, "conversation-lock-renewer");
        thread.setDaemon(true);
        return thread;
    });

    private final StringRedisTemplate redisTemplate;
    private final AgentIdempotencyGuardMapper idempotencyGuardMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.agent.conversation-lock.ttl-seconds:90}")
    private long lockTtlSeconds;

    @Value("${app.agent.conversation-lock.renew-interval-seconds:30}")
    private long renewIntervalSeconds;

    // ==================================================================
    // 一、Redis 会话锁
    // ==================================================================

    /**
     * 获取会话锁：同一租户同一会话同一时刻只允许一个 Agent Run。
     *
     * <p><b>fail-closed</b>：Redis 不可用时直接拒绝启动，绝不"跳过锁继续执行"。
     *
     * @throws IllegalStateException 会话已在处理中，或锁不可用
     */
    public ConversationLease acquire(Long tenantId, String conversationUuid) {
        requireTenant(tenantId);
        if (conversationUuid == null || conversationUuid.isBlank()) {
            throw new IllegalArgumentException("conversationUuid 不能为空");
        }
        var key = lockKey(tenantId, conversationUuid);
        var token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, token, Duration.ofSeconds(lockTtlSeconds));
        } catch (Exception e) {
            throw new IllegalStateException("会话锁不可用，出于安全考虑拒绝启动 Agent 执行", e);
        }
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("当前会话正在处理上一条消息，请等待回复完成后重试。");
        }
        var lease = new ConversationLease(key, token);
        lease.startRenewal();
        log.debug("会话锁已获取：key={}", key);
        return lease;
    }

    /** 释放会话锁：原子 compare-and-delete，旧 Lease 不能删除别人的锁。 */
    public void release(ConversationLease lease) {
        if (lease == null) {
            return;
        }
        lease.stopRenewal();
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(lease.key()), lease.token());
        } catch (Exception e) {
            // TTL 会兜底释放；清理失败不能反过来破坏已完成的业务回复
            log.warn("释放会话锁失败（将由 TTL 兜底）：key={}, error={}", lease.key(), e.getMessage());
        }
    }

    private String lockKey(Long tenantId, String conversationUuid) {
        return "omni:agent:conversation-lock:" + tenantId + ":" + conversationUuid;
    }

    /** 会话锁租约：只能由对应 Lease 释放；续期失败即视为失去锁。 */
    public final class ConversationLease {

        private final String key;
        private final String token;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean lockOwned = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> renewalTask;

        private ConversationLease(String key, String token) {
            this.key = key;
            this.token = token;
        }

        public String key() {
            return key;
        }

        public String token() {
            return token;
        }

        /** 租约是否仍然有效（未释放，且续期未失败）。 */
        public boolean isValid() {
            return active.get() && lockOwned.get();
        }

        private void startRenewal() {
            if (renewIntervalSeconds <= 0) {
                return;
            }
            renewalTask = LOCK_RENEWER.scheduleWithFixedDelay(this::renew,
                    renewIntervalSeconds, renewIntervalSeconds, TimeUnit.SECONDS);
        }

        private void renew() {
            if (!active.get()) {
                return;
            }
            try {
                var millis = String.valueOf(Duration.ofSeconds(lockTtlSeconds).toMillis());
                var renewed = redisTemplate.execute(RENEW_SCRIPT, List.of(key), token, millis);
                if (renewed == null || renewed == 0L) {
                    if (lockOwned.compareAndSet(true, false)) {
                        log.warn("会话锁续期失败：锁已易主或过期，后续禁止新的副作用工具：key={}", key);
                    }
                }
            } catch (Exception e) {
                if (lockOwned.compareAndSet(true, false)) {
                    log.warn("会话锁续期异常（视为失去锁，后续禁止新的副作用工具）：key={}, error={}",
                            key, e.getMessage());
                }
            }
        }

        private void stopRenewal() {
            active.set(false);
            lockOwned.set(false);
            var task = renewalTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    // ==================================================================
    // 二、工具白名单 + 运行时二次校验
    // ==================================================================

    /**
     * 按 SpecialistPlan 的白名单过滤 ToolCallback（注册期过滤）。
     *
     * @throws IllegalStateException 白名单配置了当前不存在的 Tool（配置错误，需尽快暴露）
     */
    public List<ToolCallback> guardedCallbacks(ToolCallback[] callbacks,
                                              AgentOrchestratorService.SpecialistPlan plan) {
        var callbackByName = Arrays.stream(callbacks)
                .collect(Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        callback -> callback));
        var missing = plan.toolAllowlist().stream()
                .filter(name -> !callbackByName.containsKey(name))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("配置的 specialist 工具缺失：" + missing);
        }
        return plan.toolAllowlist().stream()
                .map(callbackByName::get)
                .map(callback -> (ToolCallback) callback)
                .toList();
    }

    /**
     * 为单个 ToolCallback 增加运行时二次校验与副作用幂等（执行期校验）。
     *
     * <p>调用方应把它包在 {@code ToolCallbackScope} 内层，使执行时线程上下文已绑定。
     */
    public ToolCallback guard(ToolCallback delegate) {
        return new GuardedToolCallback(delegate);
    }

    /** 运行时执行：校验 ToolContext → 白名单 → 锁有效性 → 副作用幂等 → 真实工具。 */
    private String executeGuarded(ToolCallback delegate, String toolInput, ToolContext toolContext) {
        var context = toolContext == null ? Map.<String, Object>of() : toolContext.getContext();
        var toolName = delegate.getToolDefinition().name();

        var tenantId = asLong(context.get("tenantId"));
        var conversationUuid = asString(context.get("conversationUuid"));
        var traceId = asString(context.get("traceId"));
        var allowedTools = asCollection(context.get("allowedTools"));

        // 1. 服务端构建的上下文必须齐全（不接受模型或客户端提供的权限字段）
        if (tenantId == null || conversationUuid == null || conversationUuid.isBlank()
                || traceId == null || traceId.isBlank()) {
            throw new SecurityException(
                    "工具执行被拒绝：缺少服务端构建的 ToolContext（tenantId/conversationUuid/traceId）");
        }
        requireTenant(tenantId);

        // 2. 运行时再次校验白名单
        if (!allowedTools.contains(toolName)) {
            throw new SecurityException("工具执行被 specialist 白名单拒绝：" + toolName);
        }

        // 3. 只读工具直接放行（可正常重复调用，不受副作用幂等限制）
        if (!SIDE_EFFECT_TOOLS.contains(toolName)) {
            return delegate.call(toolInput, toolContext);
        }

        // 4. 副作用工具：锁必须仍然有效
        var lease = asLease(context.get("conversationLease"));
        if (lease != null && !lease.isValid()) {
            GuardOutcomeHolder.mark("LOCK_LOST", toolName);
            log.warn("副作用工具被拒绝：会话锁已失效 tool={}, traceId={}", toolName, traceId);
            return blockedPayload(toolName, "LOCK_LOST",
                    "当前会话的执行保护已失效，本次未执行该操作，请稍后重试。");
        }

        // 5. 幂等登记必须先于真实业务工具执行
        var guardKey = buildGuardKey(toolName, toolInput);
        if (!beginSideEffect(tenantId, conversationUuid, guardKey, toolName, toolInput)) {
            GuardOutcomeHolder.mark("DUPLICATE_BLOCKED", toolName);
            log.info("副作用工具被幂等拦截：tool={}, conv={}, guardKey={}",
                    toolName, conversationUuid, guardKey);
            return blockedPayload(toolName, "DUPLICATE_BLOCKED",
                    "该售后申请已提交或正在处理中，请勿重复提交。");
        }

        try {
            var output = delegate.call(toolInput, toolContext);
            updateGuard(tenantId, conversationUuid, guardKey, businessStatus(output));
            return output;
        } catch (RuntimeException e) {
            updateGuard(tenantId, conversationUuid, guardKey, "FAILED");
            throw e;
        }
    }

    /**
     * 幂等登记：靠数据库唯一键完成原子竞争。
     *
     * @return true = 登记成功（可执行真实工具）；false = 该请求已存在（不可执行）
     * @throws IllegalStateException 数据库不可用（不能假装"没有重复请求"）
     */
    private boolean beginSideEffect(Long tenantId, String conversationUuid, String guardKey,
                                    String toolName, String toolInput) {
        var guard = new AgentIdempotencyGuard();
        guard.setTenantId(tenantId);
        guard.setConversationUuid(conversationUuid);
        guard.setGuardKey(guardKey);
        guard.setToolName(toolName);
        guard.setRequestHash(sha256(canonicalJson(toolInput)));
        guard.setStatus("RECORDED");
        try {
            idempotencyGuardMapper.insert(guard);
            return true;
        } catch (DuplicateKeyException e) {
            // 真正的唯一键冲突 = 重复请求
            return false;
        } catch (RuntimeException e) {
            // 其他数据库异常：不能当作"没有重复"，必须拒绝副作用执行
            throw new IllegalStateException("幂等登记失败，出于安全考虑拒绝执行副作用工具", e);
        }
    }

    private void updateGuard(Long tenantId, String conversationUuid, String guardKey, String status) {
        try {
            idempotencyGuardMapper.update(null, new LambdaUpdateWrapper<AgentIdempotencyGuard>()
                    .eq(AgentIdempotencyGuard::getTenantId, tenantId)
                    .eq(AgentIdempotencyGuard::getConversationUuid, conversationUuid)
                    .eq(AgentIdempotencyGuard::getGuardKey, guardKey)
                    .set(AgentIdempotencyGuard::getStatus, status));
        } catch (Exception e) {
            log.warn("更新幂等状态失败（不影响业务结果）：guardKey={}, status={}, error={}",
                    guardKey, status, e.getMessage());
        }
    }

    /**
     * 依据<b>结构化业务结果</b>决定幂等记录状态。
     *
     * <p>{@code delegate.call()} 没抛异常 ≠ 业务申请创建成功：
     * <ul>
     *   <li>{@code PENDING_HUMAN_APPROVAL} / {@code PENDING} → {@code COMPLETED}（申请确实创建）；</li>
     *   <li>其他（{@code IDENTITY_VERIFICATION_REQUIRED} / 订单不存在 / 被拒绝）→ {@code FAILED}
     *       （业务拒绝，不是外部写入失败，也不自动重试）。</li>
     * </ul>
     */
    private String businessStatus(String output) {
        var status = extractStatus(output);
        if (status == null) {
            // 读不出状态时按已完成登记：幂等保护已生效，且不会重试
            return "COMPLETED";
        }
        if ("PENDING_HUMAN_APPROVAL".equals(status) || "PENDING".equals(status)) {
            return "COMPLETED";
        }
        return "FAILED";
    }

    private String extractStatus(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(output);
            var statusNode = node.get("status");
            return statusNode == null || statusNode.isNull() ? null : statusNode.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String blockedPayload(String toolName, String status, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", status, "tool", toolName, "message", message));
        } catch (Exception e) {
            return "{\"status\":\"" + status + "\",\"tool\":\"" + toolName + "\"}";
        }
    }

    // ==================================================================
    // 三、幂等键
    // ==================================================================

    /**
     * 构造稳定幂等键（任务 §25）。
     *
     * <p>策略：
     * <ul>
     *   <li>人工升级：按<b>会话</b>维度去重（同一会话不应重复创建工单）；</li>
     *   <li>审批类工具：按「订单 + 业务动作」去重，因此同一订单的退款与补发不会误共用键，
     *       而同一订单重复退款即使换了说辞也会被拦截；</li>
     *   <li>缺失业务字段时回退为规范化 JSON 哈希（字段顺序 / 空格不影响结果）。</li>
     * </ul>
     *
     * <p>键中只保留哈希，不写入邮箱、地址等敏感明文。
     */
    String buildGuardKey(String toolName, String toolInput) {
        if ("escalateToHuman".equals(toolName)) {
            // 会话维度去重（conversationUuid 已在唯一键中）
            return toolName + ":session";
        }
        var stable = stableBusinessFields(toolInput);
        if (stable != null && !stable.isBlank()) {
            return toolName + ":" + sha256(stable).substring(0, 32);
        }
        return toolName + ":" + sha256(canonicalJson(toolInput)).substring(0, 32);
    }

    /** 抽取稳定业务字段：订单号 + 业务动作。 */
    private String stableBusinessFields(String toolInput) {
        try {
            var node = objectMapper.readTree(toolInput);
            var order = firstText(node, "orderId", "orderNumber", "order_id");
            var action = firstText(node, "action", "requestType", "type");
            if (order == null && action == null) {
                return null;
            }
            return "order=" + (order == null ? "" : order) + "|action=" + (action == null ? "" : action);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstText(JsonNode node, String... keys) {
        for (var key : keys) {
            var value = node.get(key);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    /**
     * JSON 规范化：递归按字段名排序后重新序列化，消除字段顺序与空格差异。
     * 非 JSON 输入退化为去空白后的原文。
     */
    String canonicalJson(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        try {
            var node = objectMapper.readTree(input);
            return objectMapper.writeValueAsString(canonicalize(node));
        } catch (Exception e) {
            return input.replaceAll("\\s+", "");
        }
    }

    private Object canonicalize(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, Object>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonicalize(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            var list = new ArrayList<Object>();
            node.forEach(child -> list.add(canonicalize(child)));
            return list;
        }
        if (node.isTextual()) {
            return node.asText().trim();
        }
        return objectMapper.convertValue(node, Object.class);
    }

    // ==================================================================
    // 四、校验与工具方法
    // ==================================================================

    /**
     * 校验租户上下文：必须与传入 tenantId 一致。
     *
     * <p>租户来自经过验证的 Conversation（经 {@code CallScope} 绑定到执行线程），
     * 绝不使用 0 作为缺失兜底。
     */
    private void requireTenant(Long tenantId) {
        var current = TenantContextHolder.get();
        if (tenantId == null || current == null || !tenantId.equals(current)) {
            throw new SecurityException("Agent 执行需要经过验证的租户上下文");
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Collection<?> asCollection(Object value) {
        return value instanceof Collection<?> collection ? collection : List.of();
    }

    private ConversationLease asLease(Object value) {
        return value instanceof ConversationLease lease ? lease : null;
    }

    private String sha256(String value) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算请求哈希", e);
        }
    }

    /** 运行时二次校验包装：无 ToolContext 一律拒绝。 */
    private final class GuardedToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        private GuardedToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            // Spring AI 正常链路一定走 call(input, toolContext)；直接调用缺少上下文必须拒绝
            throw new SecurityException("工具执行需要 ToolContext（运行时二次校验）");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return executeGuarded(delegate, toolInput, toolContext);
        }
    }
}
