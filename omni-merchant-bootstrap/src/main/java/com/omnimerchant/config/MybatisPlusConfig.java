package com.omnimerchant.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 全局配置。
 *
 * 当前阶段主要负责：
 *
 * 1. 扫描项目中的 Mapper
 * 2. MySQL 分页
 * 3. 乐观锁（实体 @Version 生效所必需）
 * 4. createdAt / updatedAt 自动填充
 *
 * 多租户 TenantLineInnerInterceptor
 * 后续实现租户隔离时再加入。
 */
@Configuration
@MapperScan("com.omnimerchant.**.mapper")
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 核心拦截器。
     *
     * 当前只配置分页插件。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {

        MybatisPlusInterceptor interceptor =
                new MybatisPlusInterceptor();

        /*
         * MySQL 分页插件。
         *
         * 例如：
         *
         * orderMapper.selectPage(...)
         *
         * 最终会生成 LIMIT 分页 SQL。
         */
        PaginationInnerInterceptor pagination =
                new PaginationInnerInterceptor(DbType.MYSQL);

        /*
         * 单页最多查询 100 条。
         */
        pagination.setMaxLimit(100L);

        interceptor.addInnerInterceptor(pagination);

        /*
         * 乐观锁插件。
         *
         * Conversation / Customer 等实体声明了 @Version 字段；
         * MyBatis-Plus 3.5.17 的 updateById 会为 @Version 生成
         * "AND version = ?" 条件（参数 MP_OPTLOCK_VERSION_ORIGINAL），
         * 若不注册该插件，更新时会抛出 BindingException。
         */
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }


    /**
     * 自动填充：
     *
     * createdAt
     * updatedAt
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {

        return new MetaObjectHandler() {

            /**
             * INSERT 时：
             *
             * createdAt = 当前时间
             * updatedAt = 当前时间
             */
            @Override
            public void insertFill(MetaObject metaObject) {

                LocalDateTime now =
                        LocalDateTime.now();

                this.strictInsertFill(
                        metaObject,
                        "createdAt",
                        LocalDateTime.class,
                        now
                );

                this.strictInsertFill(
                        metaObject,
                        "updatedAt",
                        LocalDateTime.class,
                        now
                );
            }


            /**
             * UPDATE 时：
             *
             * updatedAt = 当前时间
             */
            @Override
            public void updateFill(MetaObject metaObject) {

                this.strictUpdateFill(
                        metaObject,
                        "updatedAt",
                        LocalDateTime.class,
                        LocalDateTime.now()
                );
            }
        };
    }
}