package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.GovernanceAuditRecordResponse;
import com.opsmind.policygovernance.application.GovernanceAuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** api-contract: "Audit API returns metadata/hash by default, not sensitive raw input." */
@RestController
public class GovernanceAuditController {

    private final GovernanceAuditService governanceAuditService;

    public GovernanceAuditController(GovernanceAuditService governanceAuditService) {
        this.governanceAuditService = governanceAuditService;
    }

    @GetMapping("/api/v1/governance-audit-records")
    public ResponseEntity<List<GovernanceAuditRecordResponse>> findByCorrelationId(@RequestParam String correlationId) {
        List<GovernanceAuditRecordResponse> records = governanceAuditService.findByCorrelationId(correlationId).stream()
            .map(GovernanceAuditRecordResponse::from)
            .toList();
        return ResponseEntity.ok(records);
    }
}
