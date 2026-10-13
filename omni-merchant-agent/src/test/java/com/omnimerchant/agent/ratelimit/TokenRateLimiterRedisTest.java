package com.omnimerchant.agent.ratelimit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Redis 执行生产 Lua；仅使用随机测试键并自动过期，不清空或删除任何业务数据。 */
@EnabledIf("redisAvailable")
class TokenRateLimiterRedisTest {
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static DefaultRedisScript<List> script;
    private List<String> keys;

    static boolean redisAvailable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @BeforeAll
    static void setUpRedis() throws Exception {
        factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        try (var input = new ClassPathResource("scripts/rate_limit.lua").getInputStream()) {
            script = new DefaultRedisScript<>(new String(input.readAllBytes(), StandardCharsets.UTF_8), List.class);
        }
    }

    @BeforeEach
    void uniqueTestKeys() {
        var prefix = "omni:test:rate:" + UUID.randomUUID();
        keys = List.of(prefix + ":qps", prefix + ":budget", prefix + ":concurrent");
    }

    @AfterAll
    static void closeRedis() {
        if (factory != null) factory.destroy();
    }

    private List<?> allow(int qps, long budget, int concurrent) {
        return redis.execute(script, keys, String.valueOf(qps), String.valueOf(budget), "1000",
                String.valueOf(concurrent), "3", "20", UUID.randomUUID().toString(), "5000",
                String.valueOf(System.currentTimeMillis()));
    }

    @DisplayName("零额度忽略已有累计用量，且不把预估值写入真实预算")
    @Test
    void zeroBudgetIsUnlimitedEvenWithExistingUsage() {
        redis.opsForValue().set(keys.get(1), "999999999", Duration.ofSeconds(20));
        assertThat(allow(5, 0, 3)).isEqualTo(List.of(1L, "OK"));
        assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("999999999");
    }

    @DisplayName("正额度按累计用量与本次预估值拒绝超限")
    @Test
    void positiveBudgetRejectsOverLimit() {
        redis.opsForValue().set(keys.get(1), "99209", Duration.ofSeconds(20));
        assertThat(allow(5, 100000, 3)).isEqualTo(List.of(0L, "BUDGET_EXCEEDED"));
        assertThat(redis.opsForZSet().size(keys.get(2))).isZero();
    }

    @DisplayName("关闭月度额度后 QPS 限制仍有效")
    @Test
    void qpsLimitStillWorks() {
        assertThat(allow(1, 0, 3)).isEqualTo(List.of(1L, "OK"));
        assertThat(allow(1, 0, 3)).isEqualTo(List.of(0L, "QPS_LIMITED"));
    }

    @DisplayName("关闭月度额度后并发限制仍有效")
    @Test
    void concurrentLimitStillWorks() {
        assertThat(allow(0, 0, 1)).isEqualTo(List.of(1L, "OK"));
        assertThat(allow(0, 0, 1)).isEqualTo(List.of(0L, "CONCURRENT_LIMITED"));
        assertThat(redis.opsForZSet().size(keys.get(2))).isEqualTo(1L);
    }
}
