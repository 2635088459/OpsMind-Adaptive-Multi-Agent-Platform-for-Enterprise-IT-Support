package com.opsmind.policygovernance.infrastructure.notification;

import com.opsmind.policygovernance.application.port.ApprovalNotificationPort;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default adapter behind {@link ApprovalNotificationPort}: logs the seam and does
 * nothing else. Active unless {@code opsmind.governance.notifications.mode=smtp}
 * (SPEC-XOBS-001 Part A), so every deployment/test that has not opted in to real
 * mail is unchanged. {@code matchIfMissing = true} keeps this the fallback when the
 * property is absent entirely.
 */
@Component
@ConditionalOnProperty(prefix = "opsmind.governance.notifications", name = "mode", havingValue = "noop", matchIfMissing = true)
public class NoOpApprovalNotificationAdapter implements ApprovalNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpApprovalNotificationAdapter.class);

    @Override
    public void notifyRequested(ApprovalRequest approvalRequest) {
        log.debug("approval requested notification suppressed (no-op adapter) approvalRequestId={}", approvalRequest.approvalRequestId());
    }

    @Override
    public void notifyDecided(ApprovalRequest approvalRequest) {
        log.debug(
            "approval decided notification suppressed (no-op adapter) approvalRequestId={} status={}",
            approvalRequest.approvalRequestId(), approvalRequest.status()
        );
    }
}
