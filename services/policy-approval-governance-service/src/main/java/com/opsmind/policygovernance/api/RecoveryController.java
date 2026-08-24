package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.PolicyDecisionResponse;
import com.opsmind.policygovernance.api.dto.RecoveryReportResponse;
import com.opsmind.policygovernance.application.PolicyDecisionService;
import com.opsmind.policygovernance.application.RecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SPEC-PG-033 (goal: "startup recovery workers", "poison decision review").
 * See {@link RecoveryService}'s own javadoc for what {@link #run} actually
 * does and does not cover.
 */
@RestController
public class RecoveryController {

    private final RecoveryService recoveryService;
    private final PolicyDecisionService policyDecisionService;

    public RecoveryController(RecoveryService recoveryService, PolicyDecisionService policyDecisionService) {
        this.recoveryService = recoveryService;
        this.policyDecisionService = policyDecisionService;
    }

    /**
     * "Startup recovery workers": runs the ordered recovery sequence on
     * demand. No {@code @PreAuthorize} scope, mirroring {@code
     * OutboxAdminController}'s own precedent for this exact category of
     * admin/deployment-triggered maintenance endpoint (baseline
     * authenticated actor only) — an external deployment script or
     * orchestrator is expected to call this once at boot, the same "admin
     * endpoint or external scheduler" seam {@code OutboxDispatchService}'s
     * own javadoc names.
     */
    @PostMapping("/api/v1/admin/recovery:run")
    public ResponseEntity<RecoveryReportResponse> run() {
        return ResponseEntity.ok(RecoveryReportResponse.from(recoveryService.runRecovery()));
    }

    /**
     * "Poison decision review": SPEC-PG-014 (11-security §Permission
     * Model: "RBAC decides whether a user can ... view audit") — gated the
     * same as any other read of a governance fact, since a {@link
     * com.opsmind.policygovernance.domain.decision.PolicyDecision} is
     * exactly that.
     */
    @PreAuthorize("hasAuthority('SCOPE_governance:audit:read')")
    @GetMapping("/api/v1/admin/recovery/poison-decisions")
    public ResponseEntity<List<PolicyDecisionResponse>> poisonDecisions() {
        List<PolicyDecisionResponse> response = policyDecisionService.findPoisonDecisions().stream()
            .map(PolicyDecisionResponse::from)
            .toList();
        return ResponseEntity.ok(response);
    }
}
