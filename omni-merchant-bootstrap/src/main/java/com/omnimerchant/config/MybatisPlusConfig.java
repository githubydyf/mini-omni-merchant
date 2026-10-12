package com.omnimerchant.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * MyBatis-Plus 全局配置。
 *
 * <p>拦截器链顺序（重要）：
 * <ol>
 *   <li>{@link TenantLineInnerInterceptor}：所有租户表 SQL 自动追加 {@code tenant_id}，
 *       并在缺少租户上下文时 <b>fail-closed</b> 抛异常；</li>
 *   <li>{@link PaginationInnerInterceptor}：MySQL 分页；</li>
 *   <li>{@link OptimisticLockerInnerInterceptor}：{@code @Version} 乐观锁。</li>
 * </ol>
 *
 * <p>全局身份表（无 tenant_id）通过 {@link StrictTenantLineHandler#ignoreTable} 排除。
 */
@Slf4j
@Configuration
@MapperScan("com.omnimerchant.**.mapper")
public class MybatisPlusConfig {

    /**
     * 无 tenant_id 的全局表（身份库 + 租户定义表），不参与租户 SQL 自动隔离。
     *
     * <p>注意：忽略 SQL 隔离不等于没有权限控制 —— {@code user_tenant_membership}
     * 虽带 tenant_id，但只由 {@code IdentityService} 通过 JdbcTemplate 访问，
     * 并在身份 Service 层严格校验用户与租户关系。
     */
    private static final Set<String> IGNORE_TABLES = Set.of(
            "tenant",
            "app_user",
            "role_permission",
            "refresh_token",
            "revoked_access_token",
            "security_audit_event",
            "user_tenant_membership"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var interceptor = new MybatisPlusInterceptor();

        // 1. 多租户 SQL 自动注入（最外层，确保所有租户表查询都带上 tenant_id）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new StrictTenantLineHandler(IGNORE_TABLES)));

        // 2. 分页插件（单页最多 100 条）
        var pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);

        // 3. 乐观锁插件（Conversation / Customer 等实体声明了 @Version）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }

    /**
     * 严格租户处理器：租户上下文缺失时直接抛出异常，绝不返回默认值或跳过条件。
     */
    public static class StrictTenantLineHandler implements TenantLineHandler {

        private final Set<String> ignoreTables;

        public StrictTenantLineHandler(Set<String> ignoreTables) {
            this.ignoreTables = ignoreTables;
        }

        @Override
        public Expression getTenantId() {
            var tenantId = TenantContextHolder.get();
            if (tenantId == null) {
                // fail-closed：宁可让请求失败，也不允许在无租户边界时执行租户表 SQL
                throw new IllegalStateException("缺少租户上下文，拒绝执行租户隔离 SQL");
            }
            return new LongValue(tenantId);
        }

        @Override
        public String getTenantIdColumn() {
            return "tenant_id";
        }

        @Override
        public boolean ignoreTable(String tableName) {
            var normalized = tableName == null ? "" : tableName.replace("`", "").toLowerCase();
            return ignoreTables.contains(normalized);
        }
    }

    /**
     * 自动填充 createdAt / updatedAt。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                var now = LocalDateTime.now();
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
