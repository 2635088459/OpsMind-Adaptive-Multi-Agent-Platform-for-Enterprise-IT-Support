package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.ArchiveAuditRecordsRequest;
import com.opsmind.policygovernance.api.dto.ArchiveAuditRecordsResponse;
import com.opsmind.policygovernance.api.dto.ComplianceReportResponse;
import com.opsmind.policygovernance.api.support.GovernanceRequestContext;
import com.opsmind.policygovernance.application.GovernanceAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC-PG-031 (goal: "Implement audit hash chain/append-only marker,
 * compliance reports, and audit retention"). Kept separate from {@link
 * GovernanceAuditController}: that controller is a pure read-by-linkage
 * query surface, while this one covers the two genuinely new capabilities
 * this spec adds — a compliance/integrity report and the retention-policy
 * archive action — mirroring how {@code OutboxAdminController} got its own
 * dedicated class rather than being folded into an existing one.
 */
@RestController
public class GovernanceAuditComplianceController {

    private final GovernanceAuditService governanceAuditService;

    public GovernanceAuditComplianceController(GovernanceAuditService governanceAuditService) {
        this.governanceAuditService = governanceAuditService;
    }

    /**
     * SPEC-PG-014 (11-security §Permission Model): "RBAC decides whether a
     * user can ... view audit" — same scope {@link GovernanceAuditController}
     * already requires, since a compliance report is itself an audit view.
     */
    @PreAuthorize("hasAuthority('SCOPE_governance:audit:read')")
    @GetMapping("/api/v1/governance-audit/compliance-report")
    public ResponseEntity<ComplianceReportResponse> complianceReport() {
        return ResponseEntity.ok(ComplianceReportResponse.from(governanceAuditService.complianceReport()));
    }

    /**
     * "Retention policy" (11-security §Tamper-Resistant Audit): archives
     * every record older than {@code retentionDays}, never deletes one. No
     * {@code @PreAuthorize} scope, mirroring {@code OutboxAdminController}'s
     * own precedent for this exact category of admin/scheduler-triggered
     * maintenance endpoint (baseline authenticated actor only).
     */
    @PostMapping("/api/v1/admin/governance-audit:archive")
    public ResponseEntity<ArchiveAuditRecordsResponse> archive(
        @Valid @RequestBody ArchiveAuditRecordsRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        int archivedCount = governanceAuditService.archiveRecordedBefore(
            request.retentionDays(), GovernanceRequestContext.actorId(authentication), request.reason(),
            GovernanceRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(new ArchiveAuditRecordsResponse(archivedCount));
    }
}
