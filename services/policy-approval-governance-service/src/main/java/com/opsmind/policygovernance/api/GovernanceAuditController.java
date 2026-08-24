package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.GovernanceAuditRecordResponse;
import com.opsmind.policygovernance.api.exception.RequestValidationException;
import com.opsmind.policygovernance.application.GovernanceAuditService;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * api-contract: "Audit API returns metadata/hash by default, not sensitive
 * raw input."
 *
 * <p>SPEC-PG-030 (goal: "governance audit chain queries by
 * ticket/source/decision/approval/policy"): {@link #find} now accepts 5
 * additional optional query params alongside the original {@code
 * correlationId} — one and only one filter is accepted per call, matching
 * {@code GovernanceAuditService}'s own one-dimension-per-method query shape
 * rather than compounding filters, since compounding was never named as a
 * requirement and each dimension already answers a complete, self-contained
 * question ("every fact touching this ticket", not "this ticket AND that
 * policy").
 */
@RestController
public class GovernanceAuditController {

    private final GovernanceAuditService governanceAuditService;

    public GovernanceAuditController(GovernanceAuditService governanceAuditService) {
        this.governanceAuditService = governanceAuditService;
    }

    /**
     * SPEC-PG-014 (11-security §Permission Model): "RBAC decides whether a
     * user can ... view audit." Exactly one of {@code correlationId}/{@code
     * ticketId}/{@code approvalRequestId}/{@code policyDecisionId}/{@code
     * sourceRequestId}/{@code policyId} must be given; zero or more than one
     * is a request-shape problem ({@link RequestValidationException} -&gt;
     * {@code 400}), not a business failure.
     */
    @PreAuthorize("hasAuthority('SCOPE_governance:audit:read')")
    @GetMapping("/api/v1/governance-audit-records")
    public ResponseEntity<List<GovernanceAuditRecordResponse>> find(
        @RequestParam(required = false) String correlationId,
        @RequestParam(required = false) String ticketId,
        @RequestParam(required = false) String approvalRequestId,
        @RequestParam(required = false) String policyDecisionId,
        @RequestParam(required = false) String sourceRequestId,
        @RequestParam(required = false) String policyId
    ) {
        List<GovernanceAuditRecord> records = query(
            correlationId, ticketId, approvalRequestId, policyDecisionId, sourceRequestId, policyId
        );
        return ResponseEntity.ok(records.stream().map(GovernanceAuditRecordResponse::from).toList());
    }

    private List<GovernanceAuditRecord> query(
        String correlationId, String ticketId, String approvalRequestId,
        String policyDecisionId, String sourceRequestId, String policyId
    ) {
        long given = Stream.of(correlationId, ticketId, approvalRequestId, policyDecisionId, sourceRequestId, policyId)
            .filter(Objects::nonNull)
            .count();
        if (given != 1) {
            throw new RequestValidationException(
                "exactly one of correlationId/ticketId/approvalRequestId/policyDecisionId/sourceRequestId/policyId is required"
            );
        }
        if (correlationId != null) {
            return governanceAuditService.findByCorrelationId(correlationId);
        }
        if (ticketId != null) {
            return governanceAuditService.findByTicketId(ticketId);
        }
        if (approvalRequestId != null) {
            return governanceAuditService.findByApprovalRequestId(approvalRequestId);
        }
        if (policyDecisionId != null) {
            return governanceAuditService.findByPolicyDecisionId(policyDecisionId);
        }
        if (sourceRequestId != null) {
            return governanceAuditService.findBySourceRequestId(sourceRequestId);
        }
        return governanceAuditService.findByPolicyId(policyId);
    }
}
