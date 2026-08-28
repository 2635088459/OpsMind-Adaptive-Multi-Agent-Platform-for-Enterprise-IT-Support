package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.EvaluateAuthorizationCommand;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.port.in.EvaluateAuthorizationUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.AuthorizationDecisionRepository;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.HashingPort;
import com.opsmind.identity.application.port.out.IdentityMetricsPort;
import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.decision.AuthorizationDecision;
import com.opsmind.identity.domain.decision.DecisionEffect;
import com.opsmind.identity.domain.decision.ReasonCode;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.stepup.AuthorizationTarget;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UC-UA-005: builds an immutable {@link AuthorizationDecision}
 * (02-business-invariants #5: "Deny by default"). {@code
 * decisionKey}/{@code inputHash} dedup (09-concurrency-and-idempotency)
 * means a retried identical evaluation returns the same decision snapshot,
 * not a new one.
 *
 * <p>SPEC-UA-014 (Authorization Context And Decision API): the
 * trusted-principal, active-role-assignment, AND resource-scope legs of the
 * five-way intersection 02-business-invariants #5 names ("trusted
 * principal, active role assignment, resource scope, ownership rule, and
 * assurance requirement") are evaluated here for real — {@link
 * com.opsmind.identity.domain.role.ResourceScope#covers} is the actual
 * scope-intersection algorithm (a broader {@code TENANT} grant satisfies a
 * narrower requirement, not just an exact-equality match), replacing the
 * naive exact-match {@link RoleAssignmentRepository#findActive} this method
 * used before SPEC-UA-014.
 *
 * <p>SPEC-UA-015 (Self Service And Resource Ownership — 02-business-invariants
 * #6: "SELF permits only resources mapped to the token subject; a
 * request-body userId cannot expand access"): the ownership leg is real too,
 * but only for {@code SELF}-scoped requirements — every other scope is an
 * organizational concern SPEC-UA-014 already fully settles. Domain 01 has no
 * cross-domain knowledge of another domain's own resource ownership (a
 * ticket's owner is domain 02's own fact), so it can only compare the
 * trusted caller's own {@code resourceOwnerId} assertion against the
 * verified {@code subjectId} — never derive ownership independently, and
 * never trust a request-body field to expand access on its own.
 *
 * <p>SPEC-UA-016 (Authentication Context And Assurance Level — {@code
 * AuthenticationAssurance}'s own javadoc: "full assurance-level computation
 * is SPEC-UA-016's job"): the assurance leg is real too, applied last and
 * independently of role/scope/ownership. When {@code requiredAssuranceLevel}/
 * {@code requiredAssuranceMethods} are present, {@code sessionId} must
 * resolve to the subject's own currently-valid {@code UserSession}; its own
 * {@code acr}/{@code amr} are compared against what is required.
 * Insufficient assurance produces {@code REQUIRE_STEP_UP}, not {@code DENY}
 * — 01-domain-model's own {@code DecisionEffect} names this third outcome
 * precisely for this case: the caller already holds a legitimate session it
 * can step up (SPEC-UA-017/018's own real challenge flow), unlike an
 * outright role/scope/ownership failure with no such path forward.
 */
@Service
public class EvaluateAuthorizationService implements EvaluateAuthorizationUseCase {

    private final AuthorizationDecisionRepository decisionRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final UserSessionRepository userSessionRepository;
    private final AuditPort auditPort;
    private final HashingPort hashingPort;
    private final IdentityMetricsPort identityMetricsPort;
    private final ClockPort clock;

    public EvaluateAuthorizationService(
        AuthorizationDecisionRepository decisionRepository, UserIdentityRepository userIdentityRepository,
        RoleAssignmentRepository roleAssignmentRepository, UserSessionRepository userSessionRepository,
        AuditPort auditPort, HashingPort hashingPort, IdentityMetricsPort identityMetricsPort, ClockPort clock
    ) {
        this.decisionRepository = decisionRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.userSessionRepository = userSessionRepository;
        this.auditPort = auditPort;
        this.hashingPort = hashingPort;
        this.identityMetricsPort = identityMetricsPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AuthorizationDecision evaluate(EvaluateAuthorizationCommand command) {
        TenantId tenantId = new TenantId(command.tenantId());
        CorrelationId correlationId = new CorrelationId(command.correlationId());
        AuthorizationTarget target = new AuthorizationTarget(command.action(), command.resourceType(), command.resourceId());

        String decisionKey = hashingPort.hash(command.tenantId() + "|" + command.subjectId() + "|" + command.action() + "|" + command.resourceType() + "|" + command.resourceId());
        String inputHash = hashingPort.hash(command.toString());
        Optional<AuthorizationDecision> existing = decisionRepository.findByDecisionKeyAndInputHash(decisionKey, inputHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.now();
        AuthorizationDecision decision = buildDecision(command, tenantId, correlationId, target, decisionKey, inputHash, now);
        AuthorizationDecision saved = decisionRepository.save(decision);

        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), tenantId, IdentityAuditAction.AUTHORIZATION_DECISION_EVALUATED, command.actorId(),
            command.subjectId(), command.resourceId(), saved.effect() == DecisionEffect.ALLOW ? AuditOutcome.SUCCESS : AuditOutcome.DENIED,
            saved.reasonCodes().isEmpty() ? null : saved.reasonCodes().get(0).value(), correlationId, now
        ));
        identityMetricsPort.recordAuthorizationDecision(saved.effect());
        return saved;
    }

    private AuthorizationDecision buildDecision(
        EvaluateAuthorizationCommand command, TenantId tenantId, CorrelationId correlationId, AuthorizationTarget target,
        String decisionKey, String inputHash, Instant now
    ) {
        UserIdentity subject = userIdentityRepository.findById(command.subjectId()).orElse(null);
        if (subject == null) {
            throw new UserIdentityNotFoundException(command.subjectId());
        }
        if (!subject.isActive()) {
            return AuthorizationDecision.denyByDefault(
                UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
                command.sessionId(), target, new ReasonCode("SUBJECT_NOT_ACTIVE"), now, correlationId
            );
        }

        if (command.requiredRole() == null) {
            return applyAssurance(command, tenantId, correlationId, target, decisionKey, inputHash, now, List.of(), List.of(), true);
        }

        // SPEC-UA-014: real scope-coverage matching (ResourceScope#covers), not a naive exact-equality lookup —
        // a broader TENANT grant now satisfies a narrower SUPPORT_QUEUE/RESOURCE requirement.
        Optional<RoleAssignment> matching = roleAssignmentRepository.findByUserIdentityId(command.subjectId()).stream()
            .filter(assignment -> assignment.isActive(now))
            .filter(assignment -> assignment.roleCode() == command.requiredRole())
            .filter(assignment -> assignment.scope().covers(command.requiredScope()))
            .findFirst();
        if (matching.isEmpty()) {
            return AuthorizationDecision.denyByDefault(
                UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
                command.sessionId(), target, new ReasonCode("NO_MATCHING_ROLE_ASSIGNMENT"), now, correlationId
            );
        }

        // SPEC-UA-015: SELF is an ownership concern, not an organizational-scope one — the caller's own
        // resourceOwnerId assertion must equal the verified subject, or this is denied even though a matching
        // SELF-scoped role assignment exists. A request-body field can never expand access on its own
        // (02-business-invariants #6): resourceOwnerId only ever narrows, since it is compared against the
        // already-verified subjectId, never substituted for it.
        boolean ownershipSatisfied = true;
        if (command.requiredScope() != null && command.requiredScope().scopeType() == ResourceScope.ScopeType.SELF) {
            ownershipSatisfied = command.resourceOwnerId() != null && command.resourceOwnerId().equals(command.subjectId());
            if (!ownershipSatisfied) {
                return AuthorizationDecision.denyByDefault(
                    UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
                    command.sessionId(), target, new ReasonCode("OWNERSHIP_NOT_SATISFIED"), now, correlationId
                );
            }
        }

        return applyAssurance(
            command, tenantId, correlationId, target, decisionKey, inputHash, now,
            List.of(command.requiredRole().name()), List.of(matching.get().scope().scopeType().name()), ownershipSatisfied
        );
    }

    /**
     * SPEC-UA-016: the last leg of the intersection, applied only once
     * role/scope/ownership already allow. No requirement at all ({@code
     * requiredAssuranceLevel} and {@code requiredAssuranceMethods} both
     * absent/empty) is trivially satisfied. Otherwise {@code sessionId} must
     * resolve to the subject's own currently-valid session — missing
     * context still defaults to {@code DENY} (02-business-invariants #12),
     * since there is no session at all to step up from — and that session's
     * own {@code acr} must equal the required level (when given) with its
     * own {@code amr} containing every required method (when given), the
     * same "must contain, not merely intersect" convention {@code
     * RolePermissionCatalog}'s own subset check already established. A
     * resolvable but insufficient session produces {@code REQUIRE_STEP_UP}.
     */
    private AuthorizationDecision applyAssurance(
        EvaluateAuthorizationCommand command, TenantId tenantId, CorrelationId correlationId, AuthorizationTarget target,
        String decisionKey, String inputHash, Instant now, List<String> evaluatedRoles, List<String> evaluatedScopes,
        boolean ownershipSatisfied
    ) {
        boolean assuranceRequired = command.requiredAssuranceLevel() != null
            || (command.requiredAssuranceMethods() != null && !command.requiredAssuranceMethods().isEmpty());
        if (!assuranceRequired) {
            return AuthorizationDecision.of(
                UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
                command.sessionId(), target, DecisionEffect.ALLOW, evaluatedRoles, evaluatedScopes, ownershipSatisfied,
                null, List.of(), List.of(), now, null, correlationId
            );
        }

        UserSession session = command.sessionId() == null ? null : userSessionRepository.findById(command.sessionId()).orElse(null);
        if (session == null || !session.isValid(now)) {
            return AuthorizationDecision.denyByDefault(
                UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
                command.sessionId(), target, new ReasonCode("SESSION_REQUIRED_FOR_ASSURANCE"), now, correlationId
            );
        }

        if (!session.assurance().satisfies(command.requiredAssuranceLevel(), command.requiredAssuranceMethods())) {
            return AuthorizationDecision.of(
                UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
                command.sessionId(), target, DecisionEffect.REQUIRE_STEP_UP, evaluatedRoles, evaluatedScopes, ownershipSatisfied,
                session.assurance().acr(), List.of(new ReasonCode("INSUFFICIENT_ASSURANCE")), List.of(), now, null, correlationId
            );
        }

        return AuthorizationDecision.of(
            UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
            command.sessionId(), target, DecisionEffect.ALLOW, evaluatedRoles, evaluatedScopes, ownershipSatisfied,
            session.assurance().acr(), List.of(), List.of(), now, null, correlationId
        );
    }

    @Override
    public AuthorizationDecision findById(String decisionId) {
        return decisionRepository.findById(decisionId)
            .orElseThrow(() -> new IllegalArgumentException("authorization decision " + decisionId + " was not found"));
    }
}
