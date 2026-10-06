package com.omnimerchant.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事务提交后触发 Helpdesk 投影（EscalationRecord → Ticket）。
 *
 * <p>复现自参考项目 {@code HelpdeskProjectionEventListener}，保留
 * “事务提交后同步” 的设计。最小适配：原项目在监听器内 set/clear
 * {@code TenantContextHolder}，当前无多租户上下文，故直接调用投影服务
 * （投影所需 tenantId 由来源 EscalationRecord 提供）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelpdeskProjectionEventListener {

    private final HelpdeskProjectionService projectionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void synchronize(HelpdeskProjectionRequestedEvent event) {
        try {
            projectionService.synchronize();
        } catch (RuntimeException error) {
            log.error("Helpdesk projection event failed: tenant={}, reason={}", event.tenantId(), error.getMessage());
        }
    }
}
