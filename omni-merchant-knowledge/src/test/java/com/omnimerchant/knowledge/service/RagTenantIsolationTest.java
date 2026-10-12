package com.omnimerchant.knowledge.service;

import com.omnimerchant.knowledge.dto.Bm25SearchResult;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 政策库跨租户隔离测试（阶段 10）。
 *
 * <p>针对 MyBatis 租户插件<b>无法覆盖</b>的 PgVector / BM25 路径：向 {@code policy_vectors}
 * 写入一条租户 9002 的<b>私有</b>政策 chunk，验证：
 * <ul>
 *   <li>租户 9001 检索时<b>不会</b>召回 9002 的私有内容；</li>
 *   <li>租户 9002（所有者）检索时<b>可以</b>召回；</li>
 *   <li>全局演示语料（tenant_id=0）对所有租户可见。</li>
 * </ul>
 * 同时验证 {@link RagTenantScope} 过滤表达式生成逻辑。
 *
 * <p>PostgreSQL 不可用时整类跳过。
 */
@EnabledIf("pgAvailable")
class RagTenantIsolationTest {

    private static final String URL = "jdbc:postgresql://localhost:5433/omni_rag";
    private static final String USER = "postgres";
    private static final String PASSWORD = "123456";
    private static final String TABLE = "policy_vectors";

    private static final long TENANT_PRIVATE = 9002L;
    private static final long TENANT_OTHER = 9001L;
    private static final String SECRET_TOKEN = "zztenantprivatemarker9002zz";

    private static JdbcTemplate jdbcTemplate;
    private static Bm25Retriever retriever;
    private static String insertedId;

    static boolean pgAvailable() {
        try (Connection ignored = java.sql.DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 插入一条租户 9002 的私有政策 chunk（无 embedding 也可被 BM25 检索）
        insertedId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO " + TABLE + " (id, content, metadata) VALUES (?, ?, ?::json)",
                UUID.fromString(insertedId),
                "TENANT-PRIVATE-POLICY " + SECRET_TOKEN + " 仅租户 9002 可见的私有退货政策。",
                "{\"source\":\"tenant-9002-private.txt\",\"chunk_index\":0,\"tenant_id\":\"9002\"}");

        retriever = new Bm25Retriever(jdbcTemplate, TABLE);
        retriever.rebuildIndex();
    }

    @AfterAll
    static void tearDown() {
        if (insertedId != null && jdbcTemplate != null) {
            jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE id = ?", UUID.fromString(insertedId));
            try {
                retriever.rebuildIndex();
            } catch (Exception ignored) {
                // 清理阶段失败不影响断言结果
            }
        }
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void otherTenantMustNotRetrievePrivateChunk() throws Exception {
        TenantContextHolder.set(TENANT_OTHER);
        List<Bm25SearchResult> results = retriever.search(SECRET_TOKEN, 10);

        assertThat(results)
                .as("租户 9001 不应召回租户 9002 的私有政策")
                .noneMatch(result -> result.content() != null && result.content().contains(SECRET_TOKEN));
    }

    @Test
    void ownerTenantMustRetrieveOwnPrivateChunk() throws Exception {
        TenantContextHolder.set(TENANT_PRIVATE);
        List<Bm25SearchResult> results = retriever.search(SECRET_TOKEN, 10);

        assertThat(results)
                .anyMatch(result -> result.content() != null && result.content().contains(SECRET_TOKEN));
    }

    @Test
    void missingTenantContextOnlySeesGlobalCorpus() throws Exception {
        TenantContextHolder.clear();
        List<Bm25SearchResult> results = retriever.search(SECRET_TOKEN, 10);

        assertThat(results)
                .as("无租户上下文时不得看到任何租户私有内容")
                .noneMatch(result -> result.content() != null && result.content().contains(SECRET_TOKEN));
    }

    @Test
    void filterExpressionIncludesGlobalAndCurrentTenant() {
        TenantContextHolder.clear();
        assertThat(RagTenantScope.filterExpression()).isEqualTo("tenant_id == '0'");

        TenantContextHolder.set(1001L);
        assertThat(RagTenantScope.filterExpression())
                .isEqualTo("(tenant_id == '0' || tenant_id == '1001')");
        assertThat(RagTenantScope.visibleTenantIds()).containsExactly(0L, 1001L);
    }

    @Test
    void visibilityRuleBlocksForeignPrivateChunk() {
        TenantContextHolder.set(1001L);

        assertThat(RagTenantScope.isVisible(0L)).isTrue();       // 全局
        assertThat(RagTenantScope.isVisible(1001L)).isTrue();    // 自有
        assertThat(RagTenantScope.isVisible(1002L)).isFalse();   // 他人私有
    }
}
