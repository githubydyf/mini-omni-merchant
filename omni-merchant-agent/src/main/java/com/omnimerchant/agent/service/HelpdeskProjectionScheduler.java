package com.omnimerchant.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omnimerchant.tenant.context.TenantContextHolder;
import com.omnimerchant.tenant.entity.Tenant;
import com.omnimerchant.tenant.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定期把 EscalationRecord 投影为 Ticket（兜底补偿）。
 *
 * <p>复现自参考项目 {@code HelpdeskProjectionScheduler}：原项目遍历 tenant 表逐个投影。
 * 本项目启用 MyBatis 租户插件后，租户表 SQL 在缺少租户上下文时会 fail-closed，
 * 因此这里必须<b>按租户逐个执行</b>：为每个租户显式绑定 {@link TenantContextHolder}，
 * 投影完成后再清理，避免全局查询或跨租户数据串扰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "omnimerchant.helpdesk", name = "projection-scheduler-enabled",
        matchIfMissing = true)
public class HelpdeskProjectionScheduler {

    private final HelpdeskProjectionService projectionService;
    private final TenantMapper tenantMapper;

    @Scheduled(initialDelayString = "${omnimerchant.helpdesk.projection-initial-delay-ms:3000}",
            fixedDelayString = "${omnimerchant.helpdesk.projection-interval-ms:60000}")
    public void synchronizeProjection() {
        try {
            // tenant 是全局表，不参与租户隔离，可在无租户上下文时查询
            List<Tenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                    .eq(Tenant::getStatus, 1));
            for (Tenant tenant : tenants) {
                var previous = TenantContextHolder.get();
                TenantContextHolder.set(tenant.getId());
                try {
                    projectionService.synchronize();
                } catch (RuntimeException error) {
                    log.error("Helpdesk projection sync failed: tenantId={}, reason={}",
                            tenant.getId(), error.getMessage());
                } finally {
                    if (previous == null) {
                        TenantContextHolder.clear();
                    } else {
                        TenantContextHolder.set(previous);
                    }
                }
            }
        } catch (RuntimeException error) {
            log.error("Helpdesk projection sync failed: reason={}", error.getMessage());
        }
    }
}
