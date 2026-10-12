package com.omnimerchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>Mapper 扫描统一由 {@code config/MybatisPlusConfig} 的
 * {@code @MapperScan("com.omnimerchant.**.mapper")} 负责，覆盖 agent / tenant 等所有模块，
 * 因此这里不再重复声明。
 */
@SpringBootApplication
@EnableScheduling
public class OmniMerchantApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmniMerchantApplication.class, args);
    }
}
