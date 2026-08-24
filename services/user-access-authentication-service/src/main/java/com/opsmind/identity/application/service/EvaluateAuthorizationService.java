package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.EvaluateAuthorizationCommand;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.port.in.EvaluateAuthorizationUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.AuthorizationDecisionRepository;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.HashingPort;
import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.decision.AuthorizationDecision;
import com.opsmind.identity.domain.decision.DecisionEffect;
import com.opsmind.identity.domain.decision.ReasonCode;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.stepup.AuthorizationTarget;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Service;

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
 * <p>Only the trusted-principal and active-role-assignment legs of the
 * five-way intersection 02-business-invariants #5 names ("trusted
 * principal, active role assignment, resource scope, ownership rule, and
 * assurance requirement") are evaluated here; resource-scope, ownership,
 * and assurance-requirement checks are SPEC-UA-014/015/016's job — see
 * {@link AuthorizationDecision}'s own javadoc.
 */
@Service
public class EvaluateAuthorizationService implements EvaluateAuthorizationUseCase {

    private final AuthorizationDecisionRepository decisionRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final AuditPort auditPort;
    private final HashingPort hashingPort;
    private final ClockPort clock;

    public EvaluateAuthorizationService(
        AuthorizationDecisionRepository decisionRepository, UserIdentityRepository userIdentityRepository,
        RoleAssignmentRepository roleAssignmentRepository, AuditPort auditPort, HashingPort hashingPort, ClockPort clock
    ) {
        this.decisionRepository = decisionRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.auditPort = auditPort;
        this.hashingPort = hashingPort;
        this.clock = clock;
    }

    @Override
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
            return AuthorizationDecision.of(
                UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
                command.sessionId(), target, DecisionEffect.ALLOW, List.of(), List.of(), true, null, List.of(), List.of(),
                now, null, correlationId
            );
        }

        Optional<RoleAssignment> matching = roleAssignmentRepository.findActive(command.subjectId(), command.requiredRole(), command.requiredScope(), now);
        if (matching.isEmpty()) {
            return AuthorizationDecision.denyByDefault(
                UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
                command.sessionId(), target, new ReasonCode("NO_MATCHING_ROLE_ASSIGNMENT"), now, correlationId
            );
        }

        return AuthorizationDecision.of(
            UUID.randomUUID().toString(), decisionKey, inputHash, tenantId, command.actorId(), command.subjectId(),
            command.sessionId(), target, DecisionEffect.ALLOW, List.of(command.requiredRole().name()),
            command.requiredScope() == null ? List.of() : List.of(command.requiredScope().scopeType().name()),
            true, null, List.of(), List.of(), now, null, correlationId
        );
    }

    @Override
    public AuthorizationDecision findById(String decisionId) {
        return decisionRepository.findById(decisionId)
            .orElseThrow(() -> new IllegalArgumentException("authorization decision " + decisionId + " was not found"));
    }
}
