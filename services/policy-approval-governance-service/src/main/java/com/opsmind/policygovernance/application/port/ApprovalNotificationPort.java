package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.domain.approval.ApprovalRequest;

/** Port to notify approvers/requesters of approval lifecycle changes. Out-of-band of the governance fact stream. */
public interface ApprovalNotificationPort {

    void notifyRequested(ApprovalRequest approvalRequest);

    void notifyDecided(ApprovalRequest approvalRequest);
}
