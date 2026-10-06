package com.omnimerchant.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期把 EscalationRecord 投影为 Ticket（兜底补偿）。
 *
 * <p>复现自参考项目 {@code HelpdeskProjectionScheduler}，保留
 * {@code @ConditionalOnProperty} + {@code @Scheduled} 的结构与配置项。
 *
 * <p>最小适配：原项目遍历 tenant 表逐个投影（依赖 TenantMapper / TenantContextHolder），
 * 当前无多租户上下文与 tenant 模块，故对全量做一次投影。
 *
 * <p>注意：这里把默认初始延迟设为 3s（原项目 30s），使当前开发阶段启动后即可
 * 看到 escalation_record 被投影成 ticket，便于联调；间隔仍为 60s。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "omnimerchant.helpdesk", name = "projection-scheduler-enabled", matchIfMissing = true)
public class HelpdeskProjectionScheduler {

    private final HelpdeskProjectionService projectionService;

    @Scheduled(initialDelayString = "${omnimerchant.helpdesk.projection-initial-delay-ms:3000}",
            fixedDelayString = "${omnimerchant.helpdesk.projection-interval-ms:60000}")
    public void synchronizeProjection() {
        try {
            projectionService.synchronize();
        } catch (RuntimeException error) {
            log.error("Helpdesk projection sync failed: reason={}", error.getMessage());
        }
    }
}
