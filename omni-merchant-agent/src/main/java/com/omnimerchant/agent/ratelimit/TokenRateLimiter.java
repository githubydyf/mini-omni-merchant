package com.omnimerchant.agent.ratelimit;

import com.omnimerchant.tenant.context.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 三层模型调用限流（Redis Lua 原子实现）。
 *
 * <p>复现自参考项目 {@code ratelimit/TokenRateLimiter}，三层含义：
 * <ol>
 *   <li><b>QPS</b> —— 单位时间请求数（固定窗口，语义见 Lua 注释）；</li>
 *   <li><b>月度 Token 预算</b> —— 预留估算值，调用后按真实用量结算；</li>
 *   <li><b>并发模型调用数</b> —— 同租户同时进行的模型调用上限。</li>
 * </ol>
 *
 * <p><b>与参考项目的差异（当前项目适配）</b>：
 * <ul>
 *   <li>当前未复现 Tenant 配额体系，配额改为 application.yml 静态配置；</li>
 *   <li>并发占用改为 <b>ZSET 租约</b>（带过期时间），避免进程崩溃后计数永久残留，
 *       且 release 幂等、不会出现负数计数；</li>
 *   <li>拒绝请求会回滚已产生的 QPS 计数，失败请求不再污染限流统计。</li>
 * </ul>
 *
 * <p><b>fail-closed</b>：Redis 不可用时拒绝请求，绝不"跳过限流直接调用模型"。
 * 这与 {@code RedisChatMemory} 的读缓存降级策略不同 —— 限流属于并发/预算保护能力。
 */
@Slf4j
@Service
public class TokenRateLimiter {

    private static final String QPS_KEY = "omni:rate:qps:%d";
    private static final String BUDGET_KEY = "omni:rate:budget:%d:%s";
    private static final String CONCURRENT_KEY = "omni:rate:concurrent:%d";
    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final StringRedisTemplate redis;

    @Value("${app.llm.rate-limit.qps-limit:5}")
    private int qpsLimit;
    @Value("${app.llm.rate-limit.monthly-token-budget:100000}")
    private long monthlyTokenBudget;
    @Value("${app.llm.rate-limit.concurrent-limit:3}")
    private int concurrentLimit;
    @Value("${app.llm.rate-limit.qps-window-seconds:1}")
    private int qpsWindowSeconds;
    /** 并发租约有效期（秒）：进程崩溃后靠它自动回收。 */
    @Value("${app.llm.rate-limit.concurrent-lease-seconds:120}")
    private int concurrentLeaseSeconds;

    private DefaultRedisScript<List> script;

    public TokenRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void init() throws Exception {
        var resource = new ClassPathResource("scripts/rate_limit.lua");
        var luaSource = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        script = new DefaultRedisScript<>(luaSource, List.class);
        log.info("限流 Lua 脚本已加载（{} 字节）", luaSource.length());
    }

    /**
     * 准入检查：三层原子判定并占用资源。
     *
     * @param tenantId        必须来自经过验证的会话租户（不得用 conversationUuid 代替）
     * @param estimatedTokens 本次预计消耗 Token（用于预算预留；真实用量由调用方结算）
     * @param leaseToken      并发租约 token（唯一）；为空时自动生成
     * @return 允许 / 拒绝（带明确原因）
     */
    public RateLimitResult allowRequest(Long tenantId, int estimatedTokens, String leaseToken) {
        if (tenantId == null) {
            log.warn("限流拒绝：缺少租户上下文");
            return RateLimitResult.reject("MISSING_TENANT");
        }
        var qpsKey = String.format(QPS_KEY, tenantId);
        var budgetKey = String.format(BUDGET_KEY, tenantId, LocalDate.now().format(YM_FMT));
        var concKey = String.format(CONCURRENT_KEY, tenantId);
        var budgetTtl = (int) Duration.ofDays(32).toSeconds();
        var leaseTtlMs = Duration.ofSeconds(concurrentLeaseSeconds).toMillis();
        var token = (leaseToken == null || leaseToken.isBlank())
                ? UUID.randomUUID().toString() : leaseToken;

        try {
            @SuppressWarnings("unchecked")
            var result = (List<Object>) redis.execute(script,
                    List.of(qpsKey, budgetKey, concKey),
                    String.valueOf(qpsLimit),
                    String.valueOf(monthlyTokenBudget),
                    String.valueOf(estimatedTokens),
                    String.valueOf(concurrentLimit),
                    String.valueOf(qpsWindowSeconds),
                    String.valueOf(budgetTtl),
                    token,
                    String.valueOf(leaseTtlMs),
                    String.valueOf(System.currentTimeMillis()));

            if (result == null || result.isEmpty()) {
                log.warn("限流脚本返回空结果，按不可用处理：tenant={}", tenantId);
                return RateLimitResult.reject("RATE_LIMITER_UNAVAILABLE");
            }
            var allowed = ((Number) result.get(0)).longValue() == 1L;
            if (!allowed) {
                var reason = result.size() > 1 ? String.valueOf(result.get(1)) : "UNKNOWN";
                log.warn("限流拒绝：tenant={}, reason={}", tenantId, reason);
                return RateLimitResult.reject(reason);
            }
            return RateLimitResult.ok();
        } catch (Exception e) {
            // fail-closed：保护不可用时拒绝，而不是放行
            log.error("限流检查失败，拒绝本次请求：tenant={}, error={}", tenantId, e.getMessage());
            return RateLimitResult.reject("RATE_LIMITER_UNAVAILABLE");
        }
    }

    /** 使用当前线程租户上下文的便捷入口（标准接口）。 */
    public RateLimitResult allowRequest(int estimatedTokens) {
        return allowRequest(TenantContextHolder.get(), estimatedTokens, null);
    }

    /**
     * 结算真实 Token 用量。
     *
     * <p>只应在拿到模型真实 Usage 时调用；拿不到真实用量时调用方应保留预留值，
     * 不得用固定估值冒充真实消耗。
     */
    public void recordTokenUsage(Long tenantId, long tokens) {
        if (tenantId == null || tokens <= 0) {
            return;
        }
        var budgetKey = String.format(BUDGET_KEY, tenantId, LocalDate.now().format(YM_FMT));
        var budgetTtl = Duration.ofDays(32);
        try {
            var incremented = redis.opsForValue().increment(budgetKey, tokens);
            if (incremented != null && incremented.equals(tokens)) {
                redis.expire(budgetKey, budgetTtl);
            }
        } catch (Exception e) {
            log.error("记录 Token 用量失败：tenant={}, error={}", tenantId, e.getMessage());
        }
    }

    public void recordTokenUsage(long tokens) {
        recordTokenUsage(TenantContextHolder.get(), tokens);
    }

    /**
     * 释放并发租约：从 ZSET 中移除本租约 token（幂等，不会产生负数计数）。
     *
     * <p>与参考项目的 {@code DECR} 不同：即使重复释放或进程崩溃，也不会让计数变负、
     * 也不会永久残留（租约分数过期后会被下次清理）。
     */
    public void releaseConcurrent(Long tenantId, String leaseToken) {
        if (tenantId == null || leaseToken == null || leaseToken.isBlank()) {
            return;
        }
        try {
            redis.opsForZSet().remove(String.format(CONCURRENT_KEY, tenantId), leaseToken);
        } catch (Exception e) {
            log.warn("释放并发租约失败（将由租约 TTL 兜底）：tenant={}, error={}",
                    tenantId, e.getMessage());
        }
    }

    /** 当前生效配额，供日志/测试观察。 */
    public int qpsLimit() {
        return qpsLimit;
    }

    public long monthlyTokenBudget() {
        return monthlyTokenBudget;
    }

    public int concurrentLimit() {
        return concurrentLimit;
    }
}
