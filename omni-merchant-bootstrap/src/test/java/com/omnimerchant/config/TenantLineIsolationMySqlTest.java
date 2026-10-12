package com.omnimerchant.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.omnimerchant.agent.entity.Conversation;
import com.omnimerchant.agent.mapper.ConversationMapper;
import com.omnimerchant.tenant.context.TenantContextHolder;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 租户隔离集成测试（阶段 10）。
 *
 * <p>不使用 Mockito：直接用 MyBatis-Plus + {@link TenantLineInnerInterceptor} +
 * {@code StrictTenantLineHandler} 连接本地 MySQL，验证租户插件能限制
 * SELECT / UPDATE / DELETE，尤其 {@code selectById(其他租户)} /
 * {@code updateById(其他租户实体)} 不能越权。
 *
 * <p>MySQL 不可用时（CI 无本地库）整类跳过，不影响其他单元测试。
 */
@EnabledIf("mysqlAvailable")
class TenantLineIsolationMySqlTest {

    private static final String URL =
            "jdbc:mysql://localhost:3306/mini-omni-merchant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "123456";

    /** 与 MybatisPlusConfig 保持一致：全局身份 / 租户定义表不参与租户隔离。 */
    private static final Set<String> IGNORE_TABLES = Set.of(
            "tenant", "app_user", "role_permission", "refresh_token",
            "revoked_access_token", "security_audit_event", "user_tenant_membership");

    private static HikariDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    static boolean mysqlAvailable() {
        try (Connection ignored = java.sql.DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(URL);
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setMaximumPoolSize(4);

        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                new MybatisPlusConfig.StrictTenantLineHandler(IGNORE_TABLES)));

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        var factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        sqlSessionFactory = factoryBean.getObject();
        // 手动注册待测 Mapper（本测试未使用 Spring 的 @MapperScan）
        sqlSessionFactory.getConfiguration().addMapper(ConversationMapper.class);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void selectByIdMustNotReturnOtherTenantRow() {
        // 会话 5006 属于租户 1002；在租户 1001 上下文中按主键查询必须为空
        TenantContextHolder.set(1001L);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ConversationMapper.class);

            assertThat(mapper.selectById(5006L)).isNull();
            // 同租户主键仍然可查
            assertThat(mapper.selectById(5001L)).isNotNull();
        }
    }

    @Test
    void selectListMustOnlyReturnCurrentTenantRows() {
        TenantContextHolder.set(1001L);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ConversationMapper.class);
            var rows = mapper.selectList(new LambdaQueryWrapper<Conversation>().last("LIMIT 50"));

            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(row -> assertThat(row.getTenantId()).isEqualTo(1001L));
        }
    }

    @Test
    void updateByIdMustNotUpdateOtherTenantRow() {
        // 在租户 1001 上下文中更新租户 1002 的会话：受影响行数必须为 0
        TenantContextHolder.set(1001L);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(ConversationMapper.class);
            var update = new LambdaUpdateWrapper<Conversation>()
                    .eq(Conversation::getId, 5006L)
                    .set(Conversation::getPriority, 9);
            var affected = mapper.update(null, update);

            assertThat(affected).isZero();
        }
    }

    @Test
    void deleteMustNotDeleteOtherTenantRow() {
        // 租户 1001 上下文中删除租户 1002 的会话：受影响行数必须为 0（逻辑删除同样被拦截）
        TenantContextHolder.set(1001L);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            var mapper = session.getMapper(ConversationMapper.class);

            assertThat(mapper.deleteById(5006L)).isZero();
        }
    }

    @Test
    void missingTenantContextMustFailClosed() {
        // fail-closed：无租户上下文时执行租户表 SQL 必须抛异常，而不是返回全量数据
        TenantContextHolder.clear();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(ConversationMapper.class);

            assertThatThrownBy(() -> mapper.selectList(new LambdaQueryWrapper<Conversation>().last("LIMIT 1")))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("租户上下文");
        }
    }
}
