package com.omnimerchant.knowledge.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PgVector / PostgreSQL 专用数据源。
 *
 * <p>从旧单体 {@code DataSourceConfig} 中迁移 PostgreSQL / PgVector 部分。
 * 本类<b>只负责</b>：
 *
 * <ul>
 *   <li>{@code pgVectorDataSource}：连 PostgreSQL 的 Hikari 数据源</li>
 *   <li>{@code pgVectorJdbcTemplate}：仅供 PgVector 与 BM25 使用</li>
 * </ul>
 *
 * <p>MySQL 业务库（MyBatis-Plus）仍由 bootstrap 负责，本类<b>不</b>重新声明
 * {@code @Primary mysqlDataSource}，避免破坏现有 MyBatis-Plus 数据源。
 *
 * <p>数据源分工：
 * <pre>
 * MySQL      → 业务库（MyBatis-Plus，@Primary）
 * PostgreSQL → RAG / PgVector（本类，限定名注入）
 * </pre>
 *
 * <p>注意：Spring Boot 的 {@code DataSourceAutoConfiguration} 在检测到已存在
 * DataSource Bean 时会跳过默认数据源自动装配，因此 PG 数据源不会抢占 @Primary。
 */
@Configuration
public class PgVectorDataSourceConfig {

    @Bean(name = "pgVectorDataSource")
    public DataSource pgVectorDataSource(
            @Value("${app.pgvector.url}") String url,
            @Value("${app.pgvector.username}") String username,
            @Value("${app.pgvector.password}") String password) {

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(
            @Qualifier("pgVectorDataSource") DataSource dataSource) {

        return new JdbcTemplate(dataSource);
    }
}
