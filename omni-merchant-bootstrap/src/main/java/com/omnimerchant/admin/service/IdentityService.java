package com.omnimerchant.admin.service;

import com.omnimerchant.common.exception.BusinessException;
import com.omnimerchant.common.exception.ErrorCode;
import com.omnimerchant.common.util.JwtUtil;
import com.omnimerchant.common.util.JwtUtil.JwtPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 身份认证与授权服务（MySQL 数据源）。
 *
 * <p>复现自参考项目 {@code admin/service/IdentityService}，全部通过 {@link JdbcTemplate}
 * 直连 MySQL：
 * <ul>
 *   <li>{@code login / refresh / logout / currentUser}；</li>
 *   <li>{@code listUsers / listRoles / createUser / setUserStatus / replaceMemberships}；</li>
 *   <li>{@code isAccessTokenRevoked / isTokenVersionCurrent}（供认证过滤器校验）；</li>
 *   <li>{@code audit(...)} 写入 {@code security_audit_event}。</li>
 * </ul>
 *
 * <p><b>安全约束</b>：用户数据来源必须是 MySQL；不得硬编码管理员账户、内存模拟用户，
 * 或用固定角色列表冒充认证结果。密码只保存 BCrypt 哈希，登录用
 * {@code passwordEncoder.matches()} 校验，绝不明文比较。
 *
 * <p>首次启动（{@link ApplicationRunner}）：当 {@code ADMIN_EMAIL / ADMIN_PASSWORD}
 * 已配置且管理员不存在时创建平台管理员；已存在则不覆盖密码。凭据缺失时明确报告配置问题。
 */
@Service
public class IdentityService implements ApplicationRunner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final String bootstrapEmail;
    private final String bootstrapPassword;
    private final long refreshExpirationMs;

    public IdentityService(JdbcTemplate jdbcTemplate,
                           PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil,
                           @Value("${admin.email:}") String bootstrapEmail,
                           @Value("${admin.password:}") String bootstrapPassword,
                           @Value("${admin.refresh-expiration-ms:2592000000}") long refreshExpirationMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.bootstrapEmail = normalizeEmail(bootstrapEmail);
        this.bootstrapPassword = bootstrapPassword;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /** 首次启动创建平台管理员（幂等；已存在则不覆盖密码）。 */
    @Override
    public void run(ApplicationArguments args) {
        Integer admins = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE platform_admin = 1", Integer.class);
        boolean adminExists = admins != null && admins > 0;
        if (bootstrapEmail.isBlank() || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            if (adminExists) {
                // 已存在平台管理员：允许不配置引导凭据，不覆盖已有密码
                return;
            }
            throw new IllegalStateException(
                    "尚未创建平台管理员，必须通过环境变量 ADMIN_EMAIL 与 ADMIN_PASSWORD 配置引导账号");
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, bootstrapEmail);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO app_user(email, password_hash, display_name, platform_admin, status)
                VALUES (?, ?, ?, 1, 'ACTIVE')
                """, bootstrapEmail, passwordEncoder.encode(bootstrapPassword), "Platform Administrator");
        audit(null, null, "BOOTSTRAP_ADMIN_CREATED", "SUCCESS", bootstrapEmail, "首次平台管理员已创建");
    }

    /** 邮箱 + 密码登录，返回 Access / Refresh 双令牌。 */
    public AuthTokens login(String email, String password) {
        var normalizedEmail = normalizeEmail(email);
        var user = findUserByEmail(normalizedEmail);
        if (user == null || password == null || !passwordEncoder.matches(password, user.passwordHash())) {
            audit(user == null ? null : user.id(), null, "LOGIN", "DENIED", normalizedEmail, "凭据错误");
            // 统一提示，避免暴露用户是否存在
            throw new BadCredentialsException("邮箱或密码错误");
        }
        if (!"ACTIVE".equals(user.status())) {
            audit(user.id(), null, "LOGIN", "DENIED", normalizedEmail, "用户已停用");
            throw new DisabledException("用户已停用");
        }
        jdbcTemplate.update("UPDATE app_user SET last_login_at = CURRENT_TIMESTAMP(3) WHERE id = ?", user.id());
        var context = loadContext(user);
        var tokens = issueTokens(context);
        audit(user.id(), null, "LOGIN", "SUCCESS", normalizedEmail, "登录成功");
        return tokens;
    }

    /** 刷新令牌轮换：校验旧令牌 → 撤销旧令牌 → 签发新令牌（事务性）。 */
    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BadCredentialsException("缺少刷新令牌");
        }
        var hash = sha256(rawRefreshToken);
        RefreshRow refresh;
        try {
            refresh = jdbcTemplate.queryForObject("""
                    SELECT id, user_id, expires_at, revoked_at
                    FROM refresh_token
                    WHERE token_hash = ?
                    FOR UPDATE
                    """, (rs, rowNum) -> new RefreshRow(
                    rs.getLong("id"),
                    rs.getLong("user_id"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()), hash);
        } catch (EmptyResultDataAccessException e) {
            throw new BadCredentialsException("刷新令牌无效");
        }
        if (refresh.revokedAt() != null || !refresh.expiresAt().isAfter(Instant.now())) {
            // 重复使用已轮换的旧令牌同样在此被拒绝
            throw new BadCredentialsException("刷新令牌已失效");
        }
        var user = findUserById(refresh.userId());
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new BadCredentialsException("用户不可用");
        }
        var context = loadContext(user);
        var replacement = issueTokens(context);
        jdbcTemplate.update("""
                UPDATE refresh_token
                SET revoked_at = CURRENT_TIMESTAMP(3), replaced_by_hash = ?
                WHERE id = ?
                """, sha256(replacement.refreshToken()), refresh.id());
        audit(user.id(), null, "TOKEN_REFRESH", "SUCCESS", user.email(), "刷新令牌已轮换");
        return replacement;
    }

    /** 退出登录：撤销 Refresh Token 并把当前 Access Token 的 jti 加入吊销表。 */
    @Transactional
    public void logout(JwtPrincipal principal, String rawRefreshToken, Instant accessExpiresAt) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            jdbcTemplate.update("""
                    UPDATE refresh_token SET revoked_at = CURRENT_TIMESTAMP(3)
                    WHERE token_hash = ? AND revoked_at IS NULL
                    """, sha256(rawRefreshToken));
        }
        if (principal != null && principal.tokenId() != null && accessExpiresAt != null) {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO revoked_access_token(token_jti, expires_at)
                    VALUES (?, ?)
                    """, principal.tokenId(), Timestamp.from(accessExpiresAt));
        }
        audit(principal == null ? null : principal.userId(), null, "LOGOUT", "SUCCESS",
                principal == null ? null : principal.subject(), "会话已注销");
    }

    /** Access Token 是否已被吊销且尚未过期。 */
    public boolean isAccessTokenRevoked(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM revoked_access_token
                WHERE token_jti = ? AND expires_at > CURRENT_TIMESTAMP(3)
                """, Integer.class, tokenId);
        return count != null && count > 0;
    }

    /** JWT 中的 tokenVersion 是否仍与当前用户一致（停用 / membership 变更后失效）。 */
    public boolean isTokenVersionCurrent(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            return true;
        }
        Integer current = jdbcTemplate.queryForObject(
                "SELECT token_version FROM app_user WHERE id = ? AND status = 'ACTIVE'",
                Integer.class, principal.userId());
        return current != null && current == principal.tokenVersion();
    }

    /** 当前登录用户信息（供 {@code /api/auth/me}）。 */
    public Map<String, Object> currentUser(JwtPrincipal principal) {
        if (principal == null) {
            throw new BadCredentialsException("未登录");
        }
        return Map.of(
                "userId", principal.userId() == null ? 0L : principal.userId(),
                "email", principal.subject(),
                "roles", principal.roles(),
                "tenantIds", principal.tenantIds(),
                "tenantRoles", principal.tenantRoles(),
                "tenantPermissions", principal.tenantPermissions(),
                "platformAdmin", principal.platformAdmin());
    }

    /** 全部后台用户（含租户 membership），供平台管理员管理。 */
    public List<UserAdminView> listUsers() {
        return jdbcTemplate.query("""
                SELECT id, email, display_name, platform_admin, status, token_version,
                       last_login_at, created_at
                FROM app_user
                ORDER BY created_at DESC
                """, (rs, rowNum) -> new UserAdminView(
                rs.getLong("id"), rs.getString("email"), rs.getString("display_name"),
                rs.getBoolean("platform_admin"), rs.getString("status"), rs.getInt("token_version"),
                timestamp(rs.getTimestamp("last_login_at")), timestamp(rs.getTimestamp("created_at")),
                memberships(rs.getLong("id"))));
    }

    /** 角色 → 权限定义（来自 role_permission 表，不硬编码）。 */
    public List<RoleView> listRoles() {
        var rows = jdbcTemplate.query("""
                SELECT role_key, permission_key
                FROM role_permission
                ORDER BY role_key, permission_key
                """, (rs, rowNum) -> new RolePermissionRow(
                rs.getString("role_key"), rs.getString("permission_key")));
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(
                        RolePermissionRow::roleKey, LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(RolePermissionRow::permission,
                                java.util.stream.Collectors.toList())))
                .entrySet().stream().map(entry -> new RoleView(entry.getKey(), entry.getValue())).toList();
    }

    /** 创建用户（BCrypt 保存初始密码；平台管理员不使用租户 membership）。 */
    @Transactional
    public UserAdminView createUser(CreateUserCommand command, Long actorUserId) {
        if (command == null || normalizeEmail(command.email()).isBlank()
                || command.password() == null || command.password().length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "邮箱有效且初始密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        var email = normalizeEmail(command.email());
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email);
        if (existing != null && existing > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户邮箱已存在");
        }
        var keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator creator = connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO app_user(email, password_hash, display_name, platform_admin, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, email);
            statement.setString(2, passwordEncoder.encode(command.password()));
            statement.setString(3, command.displayName() == null ? email : command.displayName().trim());
            statement.setBoolean(4, command.platformAdmin());
            return statement;
        };
        jdbcTemplate.update(creator, keyHolder);
        var userId = keyHolder.getKey().longValue();
        replaceMembershipRows(userId, command.platformAdmin() ? List.of() : command.memberships());
        audit(actorUserId, null, "USER_CREATED", "SUCCESS", email,
                command.platformAdmin() ? "平台管理员" : "租户用户");
        return listUsers().stream().filter(user -> user.id().equals(userId)).findFirst().orElseThrow();
    }

    /** 修改用户状态；变更后递增 tokenVersion 并吊销该用户全部 Refresh Token。 */
    @Transactional
    public UserAdminView setUserStatus(Long userId, String status, Long actorUserId) {
        var normalized = status == null ? "" : status.trim().toUpperCase();
        if (!Set.of("ACTIVE", "DISABLED", "LOCKED").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户状态仅支持 ACTIVE/DISABLED/LOCKED");
        }
        if (userId == null || (userId.equals(actorUserId) && !"ACTIVE".equals(normalized))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能停用当前登录用户");
        }
        if (jdbcTemplate.update("""
                UPDATE app_user SET status = ?, token_version = token_version + 1 WHERE id = ?
                """, normalized, userId) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        jdbcTemplate.update(
                "UPDATE refresh_token SET revoked_at = CURRENT_TIMESTAMP(3) WHERE user_id = ? AND revoked_at IS NULL",
                userId);
        audit(actorUserId, null, "USER_STATUS_CHANGED", "SUCCESS", String.valueOf(userId), normalized);
        return listUsers().stream().filter(user -> user.id().equals(userId)).findFirst().orElseThrow();
    }

    /** 整体替换用户租户 membership；变更后递增 tokenVersion 并吊销 Refresh Token。 */
    @Transactional
    public UserAdminView replaceMemberships(Long userId, List<MembershipCommand> memberships, Long actorUserId) {
        var user = findUserById(userId == null ? -1 : userId);
        if (user == null || user.platformAdmin()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户不存在或平台管理员不使用租户 membership");
        }
        replaceMembershipRows(userId, memberships);
        jdbcTemplate.update("UPDATE app_user SET token_version = token_version + 1 WHERE id = ?", userId);
        jdbcTemplate.update(
                "UPDATE refresh_token SET revoked_at = CURRENT_TIMESTAMP(3) WHERE user_id = ? AND revoked_at IS NULL",
                userId);
        audit(actorUserId, null, "USER_MEMBERSHIPS_REPLACED", "SUCCESS", String.valueOf(userId),
                "membershipCount=" + (memberships == null ? 0 : memberships.size()));
        return listUsers().stream().filter(view -> view.id().equals(userId)).findFirst().orElseThrow();
    }

    private void replaceMembershipRows(Long userId, List<MembershipCommand> memberships) {
        jdbcTemplate.update("DELETE FROM user_tenant_membership WHERE user_id = ?", userId);
        if (memberships == null) {
            return;
        }
        for (var membership : memberships) {
            if (membership == null || membership.tenantId() == null || membership.roleKey() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "tenantId 和 roleKey 必填");
            }
            var role = membership.roleKey().trim().toUpperCase();
            Integer roleExists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM role_permission WHERE role_key = ?", Integer.class, role);
            Integer tenantExists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tenant WHERE id = ? AND is_deleted = 0", Integer.class, membership.tenantId());
            if (roleExists == null || roleExists == 0 || tenantExists == null || tenantExists == 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "租户或角色不存在");
            }
            jdbcTemplate.update("""
                    INSERT INTO user_tenant_membership(user_id, tenant_id, role_key, status)
                    VALUES (?, ?, ?, 'ACTIVE')
                    """, userId, membership.tenantId(), role);
        }
    }

    private List<MembershipView> memberships(Long userId) {
        return jdbcTemplate.query("""
                SELECT tenant_id, role_key, status
                FROM user_tenant_membership WHERE user_id = ? ORDER BY tenant_id, role_key
                """, (rs, rowNum) -> new MembershipView(
                rs.getLong("tenant_id"), rs.getString("role_key"), rs.getString("status")), userId);
    }

    private Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private AuthTokens issueTokens(UserContext context) {
        var accessToken = jwtUtil.generateAccessToken(
                context.user().email(),
                context.user().id(),
                context.primaryRole(),
                context.roles(),
                context.tenantRoles(),
                context.tenantPermissions(),
                context.user().platformAdmin(),
                context.user().tokenVersion());
        var refreshToken = randomToken();
        var expiresAt = Instant.now().plusMillis(refreshExpirationMs);
        jdbcTemplate.update("""
                INSERT INTO refresh_token(user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """, context.user().id(), sha256(refreshToken), Timestamp.from(expiresAt));
        return new AuthTokens(
                accessToken,
                refreshToken,
                jwtUtil.tokenExpiresAt(accessToken).toInstant(),
                expiresAt,
                context.user().email(),
                context.user().id(),
                context.roles(),
                context.tenantRoles().keySet(),
                context.user().platformAdmin());
    }

    /** 加载用户上下文：平台管理员固定 PLATFORM_ADMIN；否则按 ACTIVE membership 汇总角色与权限。 */
    private UserContext loadContext(UserRow user) {
        if (user.platformAdmin()) {
            return new UserContext(user, "ADMIN", Set.of("PLATFORM_ADMIN"), Map.of(), Map.of());
        }
        var memberships = jdbcTemplate.query("""
                SELECT tenant_id, role_key
                FROM user_tenant_membership
                WHERE user_id = ? AND status = 'ACTIVE'
                ORDER BY tenant_id, role_key
                """, (rs, rowNum) -> new MembershipRow(rs.getLong("tenant_id"), rs.getString("role_key")), user.id());
        var roles = new LinkedHashSet<String>();
        var tenantRoles = new LinkedHashMap<Long, Set<String>>();
        var tenantPermissions = new LinkedHashMap<Long, Set<String>>();
        for (var membership : memberships) {
            roles.add(membership.roleKey());
            tenantRoles.computeIfAbsent(membership.tenantId(), ignored -> new LinkedHashSet<>())
                    .add(membership.roleKey());
            var permissions = loadPermissions(membership.roleKey());
            tenantPermissions.computeIfAbsent(membership.tenantId(), ignored -> new LinkedHashSet<>())
                    .addAll(permissions);
        }
        var primaryRole = roles.stream().findFirst().orElse("READ_ONLY_AUDITOR");
        return new UserContext(user, primaryRole, Set.copyOf(roles), immutable(tenantRoles),
                immutable(tenantPermissions));
    }

    private Set<String> loadPermissions(String roleKey) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT permission_key FROM role_permission WHERE role_key = ? ORDER BY permission_key",
                String.class, roleKey));
    }

    private UserRow findUserByEmail(String email) {
        return jdbcTemplate.query("""
                SELECT id, email, password_hash, platform_admin, status, token_version
                FROM app_user WHERE email = ?
                """, (rs, rowNum) -> mapUser(rs), email).stream().findFirst().orElse(null);
    }

    private UserRow findUserById(long userId) {
        return jdbcTemplate.query("""
                SELECT id, email, password_hash, platform_admin, status, token_version
                FROM app_user WHERE id = ?
                """, (rs, rowNum) -> mapUser(rs), userId).stream().findFirst().orElse(null);
    }

    private UserRow mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getBoolean("platform_admin"),
                rs.getString("status"),
                rs.getInt("token_version"));
    }

    private Map<Long, Set<String>> immutable(Map<Long, Set<String>> source) {
        var result = new LinkedHashMap<Long, Set<String>>();
        source.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    private void audit(Long userId, Long tenantId, String action, String outcome,
                       String subject, String detail) {
        jdbcTemplate.update("""
                INSERT INTO security_audit_event(user_id, tenant_id, action, outcome, subject_hash, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, tenantId, action, outcome,
                subject == null ? null : sha256(subject.toLowerCase()), detail);
    }

    private String randomToken() {
        var bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    public record AuthTokens(
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt,
            String email,
            Long userId,
            Set<String> roles,
            Set<Long> tenantIds,
            boolean platformAdmin) {
    }

    public record MembershipCommand(Long tenantId, String roleKey) {
    }

    public record CreateUserCommand(String email, String displayName, String password,
                                    boolean platformAdmin, List<MembershipCommand> memberships) {
    }

    public record MembershipView(Long tenantId, String roleKey, String status) {
    }

    public record UserAdminView(Long id, String email, String displayName, boolean platformAdmin,
                                String status, int tokenVersion, Instant lastLoginAt, Instant createdAt,
                                List<MembershipView> memberships) {
    }

    public record RoleView(String roleKey, List<String> permissions) {
    }

    private record UserRow(long id, String email, String passwordHash, boolean platformAdmin,
                           String status, int tokenVersion) {
    }

    private record MembershipRow(long tenantId, String roleKey) {
    }

    private record RefreshRow(long id, long userId, Instant expiresAt, Instant revokedAt) {
    }

    private record RolePermissionRow(String roleKey, String permission) {
    }

    private record UserContext(UserRow user, String primaryRole, Set<String> roles,
                               Map<Long, Set<String>> tenantRoles,
                               Map<Long, Set<String>> tenantPermissions) {
    }
}
