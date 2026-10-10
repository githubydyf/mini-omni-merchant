package com.omnimerchant.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.entity.AgentIdempotencyGuard;
import com.omnimerchant.agent.mapper.AgentIdempotencyGuardMapper;
import com.omnimerchant.agent.tool.GuardOutcomeHolder;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentExecutionGuardService 单元测试：Redis 会话锁 + 运行时二次校验 + 副作用幂等
 * （对应任务 §40 与 §41）。
 */
class AgentExecutionGuardServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final AgentIdempotencyGuardMapper guardMapper = mock(AgentIdempotencyGuardMapper.class);
    private AgentExecutionGuardService service;

    /** 幂等键构建与 update 包装依赖 MyBatis-Plus 实体元信息，单测需手动初始化。 */
    @BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AgentIdempotencyGuard.class);
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new AgentExecutionGuardService(redisTemplate, guardMapper, new ObjectMapper());
        TenantContextHolder.set(1001L);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        GuardOutcomeHolder.clear();
    }

    // ================= 一、Redis 会话锁（§40） =================

    @Test
    void firstAcquireShouldSucceed() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var lease = service.acquire(1001L, "conv-1");
        assertThat(lease.token()).isNotBlank();
        assertThat(lease.key()).isEqualTo("omni:agent:conversation-lock:1001:conv-1");
        assertThat(lease.isValid()).isTrue();
    }

    @Test
    void secondAcquireOnSameConversationShouldFail() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> service.acquire(1001L, "conv-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正在处理上一条消息");
    }

    @Test
    void differentConversationsShouldGetDistinctKeys() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var a = service.acquire(1001L, "conv-1");
        var b = service.acquire(1001L, "conv-2");
        assertThat(a.key()).isNotEqualTo(b.key());
    }

    @Test
    void differentTenantsShouldGetDistinctKeys() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var a = service.acquire(1001L, "conv-1");
        TenantContextHolder.set(1002L);
        var b = service.acquire(1002L, "conv-1");
        assertThat(a.key()).isEqualTo("omni:agent:conversation-lock:1001:conv-1");
        assertThat(b.key()).isEqualTo("omni:agent:conversation-lock:1002:conv-1");
    }

    @Test
    void redisFailureShouldFailClosed() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis 连接失败"));
        assertThatThrownBy(() -> service.acquire(1001L, "conv-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("会话锁不可用");
    }

    @Test
    void missingTenantContextShouldReject() {
        TenantContextHolder.clear();
        assertThatThrownBy(() -> service.acquire(1001L, "conv-1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void releaseShouldUseAtomicCompareAndDeleteScript() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var lease = service.acquire(1001L, "conv-1");

        service.release(lease);

        // 必须走 Lua 脚本并带上本次 token，而不是无条件 delete
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), eq(List.of(lease.key())), eq(lease.token()));
        assertThat(scriptCaptor.getValue().getScriptAsString()).contains("del");
    }

    @Test
    void staleLeaseCannotReleaseNewLock() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var lease = service.acquire(1001L, "conv-1");
        service.release(lease);
        // 脚本返回 0 表示 token 不匹配（锁已易主），此时不得删除
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(0L);
        service.release(lease);
        // 不抛异常，且仍然只通过脚本释放
        verify(redisTemplate, org.mockito.Mockito.atLeastOnce())
                .execute(any(RedisScript.class), any(List.class), any(Object[].class));
    }

    @Test
    void releaseShouldNotThrowWhenRedisUnavailable() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var lease = service.acquire(1001L, "conv-1");
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenThrow(new RuntimeException("Redis 挂了"));
        // 释放失败不能抛出，避免破坏已完成的业务回复
        service.release(lease);
    }

    // ================= 二、运行时二次校验 + 幂等（§41） =================

    private ToolCallback callback(String name, String result, AtomicInteger counter) {
        var definition = ToolDefinition.builder()
                .name(name).description("测试桩").inputSchema("{\"type\":\"object\"}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new AssertionError("正常链路不应走 call(String)");
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                counter.incrementAndGet();
                return result;
            }
        };
    }

    private ToolContext context(String toolName, Object lease) {
        var map = new java.util.HashMap<String, Object>();
        map.put("tenantId", 1001L);
        map.put("conversationUuid", "conv-1");
        map.put("traceId", "trace-1");
        map.put("allowedTools", List.of(toolName));
        if (lease != null) {
            map.put("conversationLease", lease);
        }
        return new ToolContext(map);
    }

    @Test
    void allowlistFilterShouldKeepOnlyConfiguredTools() {
        var refund = callback("requestRefundOrReplacement", "ok", new AtomicInteger());
        var order = callback("queryOrder", "ok", new AtomicInteger());
        var plan = new AgentOrchestratorService.SpecialistPlan(
                "return", "退货智能体", List.of("requestRefundOrReplacement"), "HIGH", true, true, false);

        var guarded = service.guardedCallbacks(new ToolCallback[]{refund, order}, plan);

        assertThat(guarded).extracting(c -> c.getToolDefinition().name())
                .containsExactly("requestRefundOrReplacement");
    }

    @Test
    void missingAllowlistToolShouldExposeConfigError() {
        var plan = new AgentOrchestratorService.SpecialistPlan(
                "policy_rag", "政策RAG智能体", List.of("refundPolicyRAG"), "MEDIUM", false, false, false);
        assertThatThrownBy(() -> service.guardedCallbacks(new ToolCallback[0], plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具缺失");
    }

    @Test
    void callWithoutToolContextShouldBeRejected() {
        var guarded = service.guard(callback("queryOrder", "ok", new AtomicInteger()));
        assertThatThrownBy(() -> guarded.call("{}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("ToolContext");
    }

    @Test
    void missingTraceIdShouldBeRejected() {
        var guarded = service.guard(callback("queryOrder", "ok", new AtomicInteger()));
        var ctx = new ToolContext(Map.of(
                "tenantId", 1001L, "conversationUuid", "conv-1", "allowedTools", List.of("queryOrder")));
        assertThatThrownBy(() -> guarded.call("{}", ctx))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("ToolContext");
    }

    @Test
    void tenantMismatchShouldBeRejected() {
        TenantContextHolder.set(1002L);
        var guarded = service.guard(callback("queryOrder", "ok", new AtomicInteger()));
        assertThatThrownBy(() -> guarded.call("{}", context("queryOrder", null)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void toolOutsideAllowlistShouldBeRejectedAtRuntime() {
        var guarded = service.guard(callback("requestRefundOrReplacement", "ok", new AtomicInteger()));
        var ctx = context("queryOrder", null);  // 白名单里没有该工具
        assertThatThrownBy(() -> guarded.call("{}", ctx))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void readOnlyToolCanBeCalledRepeatedly() {
        var counter = new AtomicInteger();
        var guarded = service.guard(callback("queryOrder", "{\"status\":\"OK\"}", counter));
        var ctx = context("queryOrder", null);

        guarded.call("{}", ctx);
        guarded.call("{}", ctx);

        assertThat(counter.get()).isEqualTo(2);
        verify(guardMapper, never()).insert(any(AgentIdempotencyGuard.class));
    }

    @Test
    void sideEffectShouldRegisterBeforeExecuting() {
        var counter = new AtomicInteger();
        var guarded = service.guard(callback("requestRefundOrReplacement",
                "{\"status\":\"PENDING_HUMAN_APPROVAL\"}", counter));
        when(guardMapper.insert(any(AgentIdempotencyGuard.class))).thenReturn(1);

        var result = guarded.call("{\"orderId\":\"#1002\",\"action\":\"refund\"}",
                context("requestRefundOrReplacement", null));

        assertThat(result).contains("PENDING_HUMAN_APPROVAL");
        assertThat(counter.get()).isEqualTo(1);
        var captor = ArgumentCaptor.forClass(AgentIdempotencyGuard.class);
        verify(guardMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RECORDED");
        assertThat(captor.getValue().getToolName()).isEqualTo("requestRefundOrReplacement");
    }

    @Test
    void duplicateSideEffectShouldBeBlockedWithoutExecuting() {
        var counter = new AtomicInteger();
        var guarded = service.guard(callback("requestRefundOrReplacement", "should-not-run", counter));
        when(guardMapper.insert(any(AgentIdempotencyGuard.class)))
                .thenThrow(new DuplicateKeyException("uk_agent_guard"));

        var result = guarded.call("{\"orderId\":\"#1002\",\"action\":\"refund\"}",
                context("requestRefundOrReplacement", null));

        assertThat(result).contains("DUPLICATE_BLOCKED");
        assertThat(counter.get()).isZero();   // 真实工具未执行
        // Guard 打标记，Scope 据此不推进状态机、不记 TOOL 步
        assertThat(GuardOutcomeHolder.consume().reason()).isEqualTo("DUPLICATE_BLOCKED");
    }

    @Test
    void fieldOrderDifferenceShouldNotBypassIdempotency() {
        var guarded = service.guard(callback("requestRefundOrReplacement", "x", new AtomicInteger()));
        var keyA = guardKeyOf(guarded, "{\"orderId\":\"#1002\",\"action\":\"refund\"}");
        var keyB = guardKeyOf(guarded, "{\n  \"action\": \"refund\",\n  \"orderId\": \"#1002\"\n}");
        assertThat(keyA).isEqualTo(keyB);
    }

    @Test
    void differentActionOrOrderShouldNotShareKey() {
        var guarded = service.guard(callback("requestRefundOrReplacement", "x", new AtomicInteger()));
        var refund = guardKeyOf(guarded, "{\"orderId\":\"#1002\",\"action\":\"refund\"}");
        var replacement = guardKeyOf(guarded, "{\"orderId\":\"#1002\",\"action\":\"replacement\"}");
        var otherOrder = guardKeyOf(guarded, "{\"orderId\":\"#1003\",\"action\":\"refund\"}");
        assertThat(refund).isNotEqualTo(replacement);
        assertThat(refund).isNotEqualTo(otherOrder);
    }

    @Test
    void guardKeyShouldNotLeakSensitivePlaintext() {
        var guarded = service.guard(callback("requestRefundOrReplacement", "x", new AtomicInteger()));
        var key = guardKeyOf(guarded,
                "{\"orderId\":\"#1002\",\"action\":\"refund\",\"customerEmail\":\"lucia@example.es\"}");
        assertThat(key).doesNotContain("lucia@example.es");
        assertThat(key).doesNotContain("@");
    }

    @Test
    void databaseFailureShouldRejectSideEffect() {
        var counter = new AtomicInteger();
        var guarded = service.guard(callback("requestRefundOrReplacement", "should-not-run", counter));
        when(guardMapper.insert(any(AgentIdempotencyGuard.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("数据库不可用"));

        assertThatThrownBy(() -> guarded.call("{\"orderId\":\"#1002\",\"action\":\"refund\"}",
                context("requestRefundOrReplacement", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("幂等登记失败");
        assertThat(counter.get()).isZero();
    }

    @Test
    void businessRejectionShouldBeMarkedFailedNotCompleted() {
        var guarded = service.guard(callback("requestRefundOrReplacement",
                "{\"status\":\"IDENTITY_VERIFICATION_REQUIRED\"}", new AtomicInteger()));
        when(guardMapper.insert(any(AgentIdempotencyGuard.class))).thenReturn(1);

        guarded.call("{\"orderId\":\"#1002\",\"action\":\"refund\"}",
                context("requestRefundOrReplacement", null));

        // RECORDED → FAILED（业务拒绝，不是成功创建申请）
        verify(guardMapper, org.mockito.Mockito.atLeastOnce())
                .update(any(), any());
    }

    @Test
    void toolExceptionShouldMarkFailed() {
        var definition = ToolDefinition.builder()
                .name("requestRefundOrReplacement").description("桩").inputSchema("{\"type\":\"object\"}").build();
        ToolCallback failing = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new AssertionError("unexpected");
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                throw new IllegalStateException("业务异常");
            }
        };
        var guarded = service.guard(failing);
        when(guardMapper.insert(any(AgentIdempotencyGuard.class))).thenReturn(1);

        assertThatThrownBy(() -> guarded.call("{\"orderId\":\"#1002\",\"action\":\"refund\"}",
                context("requestRefundOrReplacement", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("业务异常");
    }

    @Test
    void lockLostShouldBlockSideEffect() {
        var counter = new AtomicInteger();
        var guarded = service.guard(callback("escalateToHuman", "should-not-run", counter));
        // 构造一个已失效的租约
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var lease = service.acquire(1001L, "conv-1");
        service.release(lease);   // 释放后 isValid=false

        var result = guarded.call("{}", context("escalateToHuman", lease));

        assertThat(result).contains("LOCK_LOST");
        assertThat(counter.get()).isZero();
    }

    @Test
    void escalationSuccessShouldCompleteGuard() {
        var guarded = service.guard(callback("escalateToHuman",
                "{\"ticketId\":\"TKT-1\",\"status\":\"PENDING\"}", new AtomicInteger()));
        when(guardMapper.insert(any(AgentIdempotencyGuard.class))).thenReturn(1);

        var result = guarded.call("{}", context("escalateToHuman", null));

        assertThat(result).contains("TKT-1");
        verify(guardMapper).insert(any(AgentIdempotencyGuard.class));
    }

    /** 通过反射取出内部构造的 guardKey，用于验证键的稳定性与脱敏。 */
    private String guardKeyOf(ToolCallback guarded, String toolInput) {
        try {
            var executeGuarded = AgentExecutionGuardService.class
                    .getDeclaredMethod("buildGuardKey", String.class, String.class);
            executeGuarded.setAccessible(true);
            @SuppressWarnings("unchecked")
            var delegateField = guarded.getClass().getDeclaredField("delegate");
            delegateField.setAccessible(true);
            var delegate = (ToolCallback) delegateField.get(guarded);
            return (String) executeGuarded.invoke(service,
                    delegate.getToolDefinition().name(), toolInput);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
