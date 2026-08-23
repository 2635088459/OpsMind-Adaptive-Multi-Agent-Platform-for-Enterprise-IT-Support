package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.ExpireDueApprovalsResponse;
import com.opsmind.policygovernance.application.ApprovalExpiryService;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SPEC-PG-012: the approval expiry worker's invocation surface.
 * {@link ApprovalExpiryService#expireDue()} itself has existed since
 * SPEC-PG-001/009, but nothing called it in production — mirroring {@code
 * application.OutboxDispatchService#publishPending}'s own documented
 * convention (never wired to a {@code @Scheduled} trigger anywhere in this
 * codebase; see that class's javadoc for why), this endpoint is the "admin
 * endpoint or external scheduler" seam that class names, now given a
 * concrete implementation for the expiry worker specifically: an external
 * scheduler (e.g. a Kubernetes CronJob) is expected to call this on a fixed
 * cadence.
 */
@RestController
public class ApprovalExpiryController {

    private final ApprovalExpiryService approvalExpiryService;

    public ApprovalExpiryController(ApprovalExpiryService approvalExpiryService) {
        this.approvalExpiryService = approvalExpiryService;
    }

    @PostMapping("/api/v1/approval-requests:expire-due")
    public ResponseEntity<ExpireDueApprovalsResponse> expireDue() {
        List<ApprovalRequest> expired = approvalExpiryService.expireDue();
        return ResponseEntity.ok(ExpireDueApprovalsResponse.from(expired));
    }
}
