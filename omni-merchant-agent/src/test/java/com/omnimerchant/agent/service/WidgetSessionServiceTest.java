package com.omnimerchant.agent.service;

import com.omnimerchant.agent.dto.WidgetDtos;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.util.JwtUtil;
import com.omnimerchant.tenant.entity.Tenant;
import com.omnimerchant.tenant.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Widget 会话服务测试（阶段 11）。
 *
 * <p>覆盖：合法 tenantCode、不存在店铺、停用店铺、逻辑删除店铺、缺少 tenantCode、
 * 非法邮箱、创建真实会话、返回真实 tenantId、签发合法令牌、过期时间、令牌绑定会话。
 */
class WidgetSessionServiceTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final ConversationLifecycleService lifecycleService =
            new ConversationLifecycleService(mock(com.omnimerchant.agent.mapper.ConversationMapper.class));
    private final JwtUtil jwtUtil = new JwtUtil(
            "01234567890123456789012345678901", 86_400_000);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final StringRedisTemplate redis = redis(valueOperations);

    private final WidgetSessionService service =
            new WidgetSessionService(tenantMapper, lifecycleService, jwtUtil, redis);

    WidgetSessionServiceTest() {
        // Redis 频率限制用不到真实计数器：首次自增返回 1，不会触发限流
        when(valueOperations.increment(anyString())).thenReturn(1L);
        ReflectionTestUtils.setField(service, "sessionRateLimit", 20);
    }

    @Test
    void shouldCreateRealSessionAndIssueToken() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1001L, "OM-1001", 1, 0, 1));

        var response = service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", "Buyer@Example.com", "小飞", "zh"), "1.2.3.4");

        assertThat(response.tenantId()).isEqualTo(1001L);
        assertThat(response.tenantCode()).isEqualTo("OM-1001");
        assertThat(response.storeName()).isEqualTo("演示店铺 1001");
        assertThat(response.conversationUuid()).isNotBlank();
        assertThat(response.welcomeMessage()).isNotBlank();

        var principal = jwtUtil.parseWidgetCustomerToken(response.customerSessionToken());
        assertThat(principal.tenantId()).isEqualTo(1001L);
        assertThat(principal.tenantCode()).isEqualTo("OM-1001");
        // 令牌必须绑定刚创建的真实会话
        assertThat(principal.conversationUuid()).isEqualTo(response.conversationUuid());
        assertThat(principal.subject()).isEqualTo("buyer@example.com");
    }

    @Test
    void tokenExpiresAboutTwoHoursLater() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1001L, "OM-1001", 1, 0, 1));

        var response = service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", null, null, null), "1.2.3.4");
        var principal = jwtUtil.parseWidgetCustomerToken(response.customerSessionToken());

        var ttl = principal.expiresAt().toInstant().toEpochMilli() - System.currentTimeMillis();
        // 略小于 2 小时（已流逝少量时间），但明显大于 1 小时
        assertThat(ttl).isBetween(60L * 60 * 1000, WidgetSessionService.WIDGET_SESSION_TTL_MS);
    }

    @Test
    void missingTenantCodeShouldFail() {
        assertThatThrownBy(() -> service.createSession(
                new WidgetDtos.WidgetSessionRequest("  ", null, null, null), "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("店铺编码");
    }

    @Test
    void unknownTenantCodeShouldFail() {
        when(tenantMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.createSession(
                new WidgetDtos.WidgetSessionRequest("NOPE", null, null, null), "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("店铺不存在");
    }

    @Test
    void disabledTenantShouldFail() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1001L, "OM-1001", 0, 0, 1));

        assertThatThrownBy(() -> service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", null, null, null), "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可用");
    }

    @Test
    void logicallyDeletedTenantShouldFail() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1001L, "OM-1001", 1, 1, 1));

        assertThatThrownBy(() -> service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", null, null, null), "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("店铺不存在");
    }

    @Test
    void autoReplyDisabledTenantShouldFail() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1001L, "OM-1001", 1, 0, 0));

        assertThatThrownBy(() -> service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", null, null, null), "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("在线智能客服");
    }

    @Test
    void invalidEmailShouldFail() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1001L, "OM-1001", 1, 0, 1));

        assertThatThrownBy(() -> service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", "not-an-email", null, null), "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邮箱格式");
    }

    @Test
    void overlongNameShouldFail() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1001L, "OM-1001", 1, 0, 1));

        assertThatThrownBy(() -> service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", null, "x".repeat(200), null), "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("称呼长度");
    }

    @Test
    void shouldUseCustomWelcomeMessageFromTenant() {
        var tenant = tenant(1001L, "OM-1001", 1, 0, 1);
        tenant.setWelcomeMessage("欢迎光临本店！");
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        var response = service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", null, null, null), "1.2.3.4");

        assertThat(response.welcomeMessage()).isEqualTo("欢迎光临本店！");
    }

    @Test
    void anonymousSessionUsesAnonymousSubject() {
        when(tenantMapper.selectOne(any())).thenReturn(tenant(1001L, "OM-1001", 1, 0, 1));

        var response = service.createSession(
                new WidgetDtos.WidgetSessionRequest("OM-1001", null, null, null), "1.2.3.4");

        assertThat(jwtUtil.parseWidgetCustomerToken(response.customerSessionToken()).subject())
                .isEqualTo("anonymous");
    }

    private Tenant tenant(Long id, String code, int status, int isDeleted, int autoReply) {
        var tenant = new Tenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setStoreName("演示店铺 " + id);
        tenant.setStatus(status);
        tenant.setIsDeleted(isDeleted);
        tenant.setAutoReplyEnabled(autoReply);
        tenant.setDefaultLang("zh");
        return tenant;
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redis(ValueOperations<String, String> ops) {
        var template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenReturn(ops);
        return template;
    }
}
