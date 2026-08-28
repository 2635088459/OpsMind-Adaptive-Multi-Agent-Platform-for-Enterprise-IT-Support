package com.opsmind.identity.api.admin;

import com.opsmind.identity.application.dto.ReconciliationCountResponse;
import com.opsmind.identity.application.dto.RoleAssignmentReconciliationResult;
import com.opsmind.identity.application.port.in.ManageBreakGlassUseCase;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.application.port.in.ManageServiceIdentityUseCase;
import com.opsmind.identity.application.port.in.ManageSessionUseCase;
import com.opsmind.identity.application.port.in.ManageStepUpUseCase;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 03-state-machine's own time-driven edges — {@code RoleAssignment
 * PENDING --activate--> ACTIVE} / {@code ACTIVE --validUntil--> EXPIRED},
 * {@code UserSession ACTIVE --expiry--> EXPIRED}, {@code StepUpChallenge
 * PENDING --timeout--> EXPIRED}, {@code ServiceIdentity ACTIVE --validUntil--> RETIRED}
 * — none of which any in-process scheduler in this codebase drives (see
 * {@code OutboxDispatchService}'s own javadoc for why); an admin or an
 * external scheduler calls these instead.
 */
@RestController
public class ReconciliationController {

    private final ManageRoleAssignmentUseCase manageRoleAssignmentUseCase;
    private final ManageSessionUseCase manageSessionUseCase;
    private final ManageStepUpUseCase manageStepUpUseCase;
    private final ManageServiceIdentityUseCase manageServiceIdentityUseCase;
    private final ManageBreakGlassUseCase manageBreakGlassUseCase;
    private final ProvisionUserUseCase provisionUserUseCase;

    public ReconciliationController(
        ManageRoleAssignmentUseCase manageRoleAssignmentUseCase, ManageSessionUseCase manageSessionUseCase,
        ManageStepUpUseCase manageStepUpUseCase, ManageServiceIdentityUseCase manageServiceIdentityUseCase,
        ManageBreakGlassUseCase manageBreakGlassUseCase, ProvisionUserUseCase provisionUserUseCase
    ) {
        this.manageRoleAssignmentUseCase = manageRoleAssignmentUseCase;
        this.manageSessionUseCase = manageSessionUseCase;
        this.manageStepUpUseCase = manageStepUpUseCase;
        this.manageServiceIdentityUseCase = manageServiceIdentityUseCase;
        this.manageBreakGlassUseCase = manageBreakGlassUseCase;
        this.provisionUserUseCase = provisionUserUseCase;
    }

    @PostMapping("/internal/identity/v1/admin/role-assignments/reconcile")
    public ResponseEntity<RoleAssignmentReconciliationResult> reconcileRoleAssignments() {
        return ResponseEntity.ok(manageRoleAssignmentUseCase.reconcileDueTransitions());
    }

    @PostMapping("/internal/identity/v1/admin/sessions/reconcile")
    public ResponseEntity<ReconciliationCountResponse> reconcileSessions() {
        return ResponseEntity.ok(new ReconciliationCountResponse(manageSessionUseCase.reconcileExpired()));
    }

    /**
     * SPEC-UA-009: drives {@code REVOKED} sessions' IdP end-session
     * notification independently of {@link #reconcileSessions}, since the
     * two never share a transaction (08-transaction-and-outbox forbids an
     * external IdP call inside a DB transaction) — see {@code
     * ManageSessionService#reconcileEndSessionNotifications}'s own javadoc.
     */
    @PostMapping("/internal/identity/v1/admin/sessions/reconcile-end-session-notifications")
    public ResponseEntity<ReconciliationCountResponse> reconcileEndSessionNotifications() {
        return ResponseEntity.ok(new ReconciliationCountResponse(manageSessionUseCase.reconcileEndSessionNotifications()));
    }

    /** SPEC-UA-033 (10-failure-handling: "Delayed revocation event | ... | Reconciliation scan"): revokes ACTIVE sessions whose own owning user identity is no longer ACTIVE. */
    @PostMapping("/internal/identity/v1/admin/sessions/reconcile-inactive-identities")
    public ResponseEntity<ReconciliationCountResponse> reconcileSessionsForInactiveIdentities() {
        return ResponseEntity.ok(new ReconciliationCountResponse(manageSessionUseCase.reconcileForInactiveIdentities()));
    }

    @PostMapping("/internal/identity/v1/admin/step-up/reconcile")
    public ResponseEntity<ReconciliationCountResponse> reconcileStepUpChallenges() {
        return ResponseEntity.ok(new ReconciliationCountResponse(manageStepUpUseCase.reconcileExpired()));
    }

    @PostMapping("/internal/identity/v1/admin/service-identities/reconcile")
    public ResponseEntity<ReconciliationCountResponse> reconcileServiceIdentities() {
        return ResponseEntity.ok(new ReconciliationCountResponse(manageServiceIdentityUseCase.reconcileRetired()));
    }

    /** SPEC-UA-019: {@code ACTIVE} break-glass grants past their own bounded {@code expiresAt}. */
    @PostMapping("/internal/identity/v1/admin/break-glass/reconcile")
    public ResponseEntity<ReconciliationCountResponse> reconcileBreakGlassGrants() {
        return ResponseEntity.ok(new ReconciliationCountResponse(manageBreakGlassUseCase.reconcileExpired()));
    }

    /** SPEC-UA-031 (07-data-model): redacts PII for {@code DEPROVISIONED} identities past their own retention window. */
    @PostMapping("/internal/identity/v1/admin/user-identities/reconcile-privacy-retention")
    public ResponseEntity<ReconciliationCountResponse> reconcilePrivacyRetention() {
        return ResponseEntity.ok(new ReconciliationCountResponse(provisionUserUseCase.reconcilePrivacyRetention()));
    }
}
