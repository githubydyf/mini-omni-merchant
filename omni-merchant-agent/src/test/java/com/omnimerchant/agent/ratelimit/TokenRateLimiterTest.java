package com.omnimerchant.agent.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenRateLimiter 单元测试（任务 §41，用 mock Redis 验证判定与释放逻辑；
 * 真实 Redis 行为见联调）。
 */
class TokenRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
    private TokenRateLimiter limiter;

    @BeforeEach
    void setUp() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        limiter = new TokenRateLimiter(redis);
        // 直接注入配额，跳过 @Value 注入
        ReflectionTestUtils.setField(limiter, "qpsLimit", 5);
        ReflectionTestUtils.setField(limiter, "monthlyTokenBudget", 100000L);
        ReflectionTestUtils.setField(limiter, "concurrentLimit", 3);
        ReflectionTestUtils.setField(limiter, "qpsWindowSeconds", 1);
        ReflectionTestUtils.setField(limiter, "concurrentLeaseSeconds", 120);
        // 加载真实 Lua 脚本（不执行，仅确保资源存在且可编译为脚本）
        limiter.init();
    }

    private void scriptReturns(Object... values) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(values));
    }

    @Test
    void missingTenantShouldReject() {
        var result = limiter.allowRequest(null, 100, "lease-1");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("MISSING_TENANT");
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void redisUnavailableShouldFailClosed() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("Redis 连接失败"));
        var result = limiter.allowRequest(1001L, 100, "lease-1");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("RATE_LIMITER_UNAVAILABLE");
    }

    @Test
    void allowedWhenAllLayersPass() {
        scriptReturns(1L, "OK");
        var result = limiter.allowRequest(1001L, 1000, "lease-1");
        assertThat(result.allowed()).isTrue();
        assertThat(result.rejectReason()).isNull();
    }

    @Test
    void qpsLimitedShouldBeRejected() {
        scriptReturns(0L, "QPS_LIMITED");
        var result = limiter.allowRequest(1001L, 1000, "lease-1");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("QPS_LIMITED");
    }

    @Test
    void budgetExceededShouldBeRejected() {
        scriptReturns(0L, "BUDGET_EXCEEDED");
        var result = limiter.allowRequest(1001L, 1000, "lease-1");
        assertThat(result.rejectReason()).isEqualTo("BUDGET_EXCEEDED");
    }

    @Test
    void concurrentLimitedShouldBeRejected() {
        scriptReturns(0L, "CONCURRENT_LIMITED");
        var result = limiter.allowRequest(1001L, 1000, "lease-1");
        assertThat(result.rejectReason()).isEqualTo("CONCURRENT_LIMITED");
    }

    @Test
    void differentTenantsShouldUseDifferentKeys() {
        scriptReturns(1L, "OK");
        limiter.allowRequest(1001L, 1000, "lease-1");
        limiter.allowRequest(1002L, 1000, "lease-2");

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis, org.mockito.Mockito.times(2))
                .execute(any(RedisScript.class), keyCaptor.capture(), any(Object[].class));
        var firstKeys = (List<?>) keyCaptor.getAllValues().get(0);
        var secondKeys = (List<?>) keyCaptor.getAllValues().get(1);
        assertThat(firstKeys.get(0)).isEqualTo("omni:rate:qps:1001");
        assertThat(secondKeys.get(0)).isEqualTo("omni:rate:qps:1002");
    }

    @Test
    void releaseConcurrentShouldRemoveLeaseFromZset() {
        limiter.releaseConcurrent(1001L, "lease-1");
        verify(zSetOps).remove("omni:rate:concurrent:1001", "lease-1");
    }

    @Test
    void releaseWithoutTenantOrLeaseShouldBeNoop() {
        limiter.releaseConcurrent(null, "lease-1");
        limiter.releaseConcurrent(1001L, null);
        limiter.releaseConcurrent(1001L, "  ");
        verify(zSetOps, never()).remove(anyString(), anyString());
    }

    @Test
    void releaseFailureShouldNotThrow() {
        when(zSetOps.remove(anyString(), anyString())).thenThrow(new RuntimeException("Redis 故障"));
        // 释放失败不能抛出（租约 TTL 会兜底）
        limiter.releaseConcurrent(1001L, "lease-1");
    }

    @Test
    void recordTokenUsageShouldIncrementBudget() {
        when(valueOps.increment(anyString(), anyLong())).thenReturn(1500L);
        limiter.recordTokenUsage(1001L, 1500L);
        verify(valueOps).increment(anyString(), anyLong());
    }

    @Test
    void recordZeroOrNegativeTokensShouldBeIgnored() {
        limiter.recordTokenUsage(1001L, 0);
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void recordTokenUsageShouldSwallowRedisFailure() {
        when(valueOps.increment(anyString(), anyLong())).thenThrow(new RuntimeException("Redis 故障"));
        limiter.recordTokenUsage(1001L, 1000L);
    }

    @org.junit.jupiter.api.DisplayName("零月度预算及其他配额按正确顺序传给 Lua，月份和租户来自本轮调用")
    @Test
    void unlimitedBudgetAndLuaArgumentOrderAreCorrect() {
        ReflectionTestUtils.setField(limiter, "monthlyTokenBudget", 0L);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
            var raw = inv.getRawArguments();
            var month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            assertThat(((List<?>) raw[1]).toArray()).containsExactly("omni:rate:qps:1002", "omni:rate:budget:1002:" + month,
                    "omni:rate:concurrent:1002");
            var arguments = (Object[]) raw[2];
            assertThat(arguments).hasSize(9);
            assertThat(java.util.Arrays.copyOf(arguments, 8)).containsExactly("5", "0", "1000", "3", "1",
                    "2764800", "测试租约", "120000");
            assertThat(Long.parseLong((String) arguments[8])).isGreaterThan(0);
            return List.of(1L, "OK");
        });
        assertThat(limiter.allowRequest(1002L, 1000, "测试租约").allowed()).isTrue();
        // 准入阶段不把估算值写成真实累计用量。
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @org.junit.jupiter.api.DisplayName("Spring 默认和显式配置均能关闭月度预算，同时保留 QPS 与并发保护")
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void springLoadsUnlimitedBudget(boolean explicit) {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            if (explicit) {
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                        "测试限流配置", java.util.Map.of("app.llm.rate-limit.monthly-token-budget", "0")));
            }
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.register(TokenRateLimiter.class);
            context.refresh();
            var actual = context.getBean(TokenRateLimiter.class);
            assertThat(actual.monthlyTokenBudget()).isZero();
            assertThat(actual.qpsLimit()).isEqualTo(5);
            assertThat(actual.concurrentLimit()).isEqualTo(3);
        }
    }
}
