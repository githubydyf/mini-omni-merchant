package com.omnimerchant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 业务主数据源（MySQL）。
 *
 * <p>为什么需要显式声明：Spring Boot 的 {@code DataSourceAutoConfiguration}
 * 只在容器中<b>不存在</b> DataSource Bean 时才装配默认数据源。现在
 * {@code omni-merchant-knowledge} 定义了 {@code pgVectorDataSource}
 * （PostgreSQL / PgVector），会自动关闭该自动配置，导致 MyBatis-Plus
 * 失去默认 DataSource。因此这里显式声明 MySQL 主数据源并标记 {@link Primary}。
 *
 * <p>数据源分工：
 * <pre>
 * MySQL      → 业务库（本类，@Primary，MyBatis-Plus 默认使用）
 * PostgreSQL → RAG / PgVector（knowledge 模块的 pgVectorDataSource，限定名注入）
 * </pre>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
