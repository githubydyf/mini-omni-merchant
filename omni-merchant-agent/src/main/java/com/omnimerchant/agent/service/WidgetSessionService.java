package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnimerchant.agent.dto.WidgetDtos;
import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.common.util.JwtUtil;
import com.omnimerchant.tenant.context.TenantContextHolder;
import com.omnimerchant.tenant.entity.Tenant;
import com.omnimerchant.tenant.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Widget 买家会话服务：根据真实店铺编码建立会话并签发短期买家令牌。
 *
 * <p>复现自参考项目 {@code CommercePlatformService.createWidgetSession}，关键约束：
 * <ul>
 *   <li>tenantCode 必须能命中真实 {@code tenant} 表记录，且店铺启用、未逻辑删除、已开启在线咨询；</li>
 *   <li>绝不用默认/任意 tenantId 兜底；tenantId 只来自真实店铺查询结果；</li>
 *   <li>会话复用统一的 {@link ConversationLifecycleService}，不新建 Widget 会话表；</li>
 *   <li>返回的 {@code customerSessionToken} 是合法签名的 Widget 买家令牌（TTL 2 小时）；</li>
 *   <li>买家填写的邮箱仅登记为联系信息，<b>不</b>构成订单身份核验。</li>
 * </ul>
 *
 * <p>公共接口滥用防护：对 {@code (IP, tenantCode)} 做最小固定窗口频率限制（Redis）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WidgetSessionService {

    /** Widget 买家会话令牌有效期：2 小时（与原项目一致）。 */
    public static final long WIDGET_SESSION_TTL_MS = 2 * 60 * 60 * 1000L;

    /** 会话创建频率限制：同一 (IP, 店铺) 在窗口内最多创建次数。 */
    private static final String SESSION_RATE_KEY = "omni:widget:session:%s:%s";
    private static final int SESSION_RATE_LIMIT = 20;
    private static final Duration SESSION_RATE_WINDOW = Duration.ofMinutes(10);

    /** 本阶段仅登记、不翻译：允许登记的语言集合，其余回退到店铺默认语言。 */
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("zh", "en");

    private static final String DEFAULT_WELCOME = "您好，这里是智能客服，请问有什么可以帮您？";
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_EMAIL_LENGTH = 191;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final TenantMapper tenantMapper;
    private final ConversationLifecycleService conversationLifecycleService;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @Value("${app.widget.session-rate-limit-per-tenant:20}")
    private int sessionRateLimit;

    /**
     * 创建买家会话。
     *
     * @param request  店铺编码 + 可选邮箱/称呼/语言
     * @param clientIp 调用方 IP（用于频率限制，可为空）
     */
    @Transactional
    public WidgetDtos.WidgetSessionResponse createSession(WidgetDtos.WidgetSessionRequest request, String clientIp) {
        if (request == null || request.tenantCode() == null || request.tenantCode().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少店铺编码 tenantCode");
        }
        var tenantCode = request.tenantCode().trim();
        if (tenantCode.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "店铺编码格式不正确");
        }
        var customerEmail = normalizeEmail(request.customerEmail());
        var customerName = normalizeName(request.customerName());

        checkSessionRate(clientIp, tenantCode);

        var tenant = findTenantByCode(tenantCode);
        ensureTenantAcceptsWidget(tenant);

        // 会话创建必须带租户上下文，租户插件才会写入正确的 tenant_id
        var previous = TenantContextHolder.get();
        TenantContextHolder.set(tenant.getId());
        try {
            var language = resolveLanguage(request.language(), tenant.getDefaultLang());
            var conversation = conversationLifecycleService.create(
                    tenant.getId(), "WEB_WIDGET", customerEmail, customerName);
            if (language != null) {
                conversation.setLanguage(language);
            }

            var welcome = tenant.getWelcomeMessage() == null || tenant.getWelcomeMessage().isBlank()
                    ? DEFAULT_WELCOME : tenant.getWelcomeMessage();
            var expiresAt = Instant.now().plusMillis(WIDGET_SESSION_TTL_MS);
            var subject = customerEmail == null || customerEmail.isBlank() ? "anonymous" : customerEmail;
            var token = jwtUtil.generateWidgetCustomerToken(subject, tenant.getId(), tenant.getTenantCode(),
                    conversation.getConversationUuid(), java.util.Date.from(expiresAt));

            log.info("创建 Widget 会话：tenantId={}, conversationUuid={}",
                    tenant.getId(), conversation.getConversationUuid());
            return new WidgetDtos.WidgetSessionResponse(
                    tenant.getId(), tenant.getTenantCode(), conversation.getConversationUuid(),
                    tenant.getStoreName(), welcome, token, expiresAt.toString());
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previous);
            }
        }
    }

    /** 按真实编码查找店铺（tenant 表为全局表，不参与租户隔离）。 */
    public Tenant findTenantByCode(String tenantCode) {
        var tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantCode, tenantCode)
                .last("LIMIT 1"));
        if (tenant == null) {
            throw new BusinessException(ErrorCode.TENANT_NOT_FOUND, "店铺不存在，请确认店铺编码");
        }
        return tenant;
    }

    /** 店铺是否可提供在线咨询：启用、未删除、已开启 AI 自动回复。 */
    private void ensureTenantAcceptsWidget(Tenant tenant) {
        if (tenant.getIsDeleted() != null && tenant.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.TENANT_NOT_FOUND, "店铺不存在，请确认店铺编码");
        }
        if (tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new BusinessException(ErrorCode.TENANT_DISABLED, "该店铺当前不可用，暂时无法在线咨询");
        }
        if (tenant.getAutoReplyEnabled() != null && tenant.getAutoReplyEnabled() == 0) {
            throw new BusinessException(ErrorCode.TENANT_DISABLED, "该店铺暂未开通在线智能客服");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        var normalized = email.trim().toLowerCase();
        if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        return normalized;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var normalized = name.trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "称呼长度不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        return normalized;
    }

    private String resolveLanguage(String requested, String tenantDefault) {
        if (requested != null && !requested.isBlank()) {
            var normalized = requested.trim().toLowerCase();
            if (SUPPORTED_LANGUAGES.contains(normalized)) {
                return normalized;
            }
        }
        return tenantDefault == null || tenantDefault.isBlank() ? "zh" : tenantDefault;
    }

    /**
     * 最小频率限制：同一 (IP, 店铺) 每 10 分钟最多创建 {@link #SESSION_RATE_LIMIT} 次会话。
     *
     * <p>Redis 不可用时放行（公共入口以保证可用性优先），只记录告警；字段长度与邮箱格式校验仍会拦住明显异常请求。
     */
    private void checkSessionRate(String clientIp, String tenantCode) {
        var ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        var key = String.format(SESSION_RATE_KEY, tenantCode, ip);
        try {
            var count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, SESSION_RATE_WINDOW);
            }
            if (count != null && count > sessionRateLimit) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "会话创建过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Widget 会话频率限制检查失败（放行）：tenantCode={}, error={}", tenantCode, e.getMessage());
        }
    }
}
