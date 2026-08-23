package com.opsmind.policygovernance.infrastructure.notification;

import com.opsmind.policygovernance.application.port.ApprovalNotificationPort;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * No-op placeholder behind {@link ApprovalNotificationPort}. A real
 * notification channel (email/chat/webhook) is out of scope for the
 * governance domain roadmap as currently specced; this only logs so the
 * seam is observable in the meantime.
 */
@Component
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
