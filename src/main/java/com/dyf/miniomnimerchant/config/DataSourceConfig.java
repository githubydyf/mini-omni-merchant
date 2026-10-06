package com.dyf.miniomnimerchant.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    /**
     * MySQL 业务数据库
     * MyBatis-Plus 默认使用这个数据源
     */
    @Primary
    @Bean(name = "mysqlDataSource")
    public DataSource mysqlDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name}") String driverClassName
    ) {

        HikariDataSource dataSource =
                new HikariDataSource();

        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(driverClassName);

        return dataSource;
    }


    /**
     * PostgreSQL / PgVector 专用数据源
     */
    @Bean(name = "pgVectorDataSource")
    public DataSource pgVectorDataSource(
            @Value("${app.pgvector.url}") String url,
            @Value("${app.pgvector.username}") String username,
            @Value("${app.pgvector.password}") String password
    ) {

        HikariDataSource dataSource =
                new HikariDataSource();

        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(
                "org.postgresql.Driver"
        );

        return dataSource;
    }


    /**
     * PgVector 专用 JdbcTemplate
     */
    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(
            @Qualifier("pgVectorDataSource")
            DataSource dataSource
    ) {

        return new JdbcTemplate(dataSource);
    }
}




