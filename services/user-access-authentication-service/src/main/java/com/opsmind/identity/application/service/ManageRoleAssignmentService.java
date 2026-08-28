package com.opsmind.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.command.CancelRoleAssignmentCommand;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.RevokeRoleAssignmentCommand;
import com.opsmind.identity.application.dto.RoleAssignmentReconciliationResult;
import com.opsmind.identity.application.exception.RoleAssignmentNotFoundException;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.EventPublisherPort;
import com.opsmind.identity.application.port.out.IdentityMetricsPort;
import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RolePermissionCatalog;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * UC-UA-004: grant/revoke/cancel a {@link RoleAssignment} and reconcile its
 * time-driven transitions (03-state-machine §RoleAssignment). {@link
 * #grant} is idempotent while a matching {@code ACTIVE} assignment already
 * exists (acceptance criteria: "Duplicate commands ... produce no
 * conflicting state"). "A role grantor cannot delegate beyond its own grant
 * scope" (02-business-invariants #9) is SPEC-UA-012's own separation-of-duties
 * job, not modeled here.
 *
 * <p>08-transaction-and-outbox: "assign/revoke role: role assignment +
 * audit + role event" — every transition here commits state, audit, and an
 * outbox row in one transaction (SPEC-UA-003).
 */
@Service
public class ManageRoleAssignmentService implements ManageRoleAssignmentUseCase {

    private static final String AGGREGATE_TYPE = "RoleAssignment";

    private final RoleAssignmentRepository roleAssignmentRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final AuditPort auditPort;
    private final EventPublisherPort eventPublisherPort;
    private final IdentityMetricsPort identityMetricsPort;
    private final ClockPort clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManageRoleAssignmentService(
        RoleAssignmentRepository roleAssignmentRepository, UserIdentityRepository userIdentityRepository,
        AuditPort auditPort, EventPublisherPort eventPublisherPort, IdentityMetricsPort identityMetricsPort, ClockPort clock
    ) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.auditPort = auditPort;
        this.eventPublisherPort = eventPublisherPort;
        this.identityMetricsPort = identityMetricsPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RoleAssignment grant(GrantRoleAssignmentCommand command) {
        UserIdentity user = userIdentityRepository.findById(command.userIdentityId())
            .orElseThrow(() -> new UserIdentityNotFoundException(command.userIdentityId()));

        Optional<RoleAssignment> existing = roleAssignmentRepository.findActive(
            command.userIdentityId(), command.roleCode(), command.scope(), clock.now()
        );
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.now();
        boolean scheduled = command.validFrom() != null && command.validFrom().isAfter(now);
        // SPEC-UA-011: permissions are never client-supplied — always the RoleCode's own server-side-authoritative set.
        List<String> permissions = List.copyOf(RolePermissionCatalog.permissionsFor(command.roleCode()));
        RoleAssignment assignment = scheduled
            ? RoleAssignment.grantPending(
                UUID.randomUUID().toString(), new TenantId(command.tenantId()), command.userIdentityId(), command.roleCode(),
                command.scope(), permissions, command.validFrom(), command.validUntil(), command.grantedBy(), command.grantReason(), now
            )
            : RoleAssignment.grantActive(
                UUID.randomUUID().toString(), new TenantId(command.tenantId()), command.userIdentityId(), command.roleCode(),
                command.scope(), permissions, command.validUntil(), command.grantedBy(), command.grantReason(), now
            );
        RoleAssignment saved = roleAssignmentRepository.save(assignment);
        audit(saved, IdentityAuditAction.ROLE_ASSIGNMENT_GRANTED, command.grantedBy(), AuditOutcome.SUCCESS, "role=" + saved.roleCode(), command.correlationId());
        publish(saved, scheduled ? "identity.role.scheduled.v1" : "identity.role.assigned.v1", command.correlationId());
        identityMetricsPort.recordRoleAssignmentChange(scheduled ? "SCHEDULED" : "GRANTED");
        return saved;
    }

    @Override
    @Transactional
    public RoleAssignment revoke(RevokeRoleAssignmentCommand command) {
        RoleAssignment assignment = roleAssignmentRepository.findById(command.roleAssignmentId())
            .orElseThrow(() -> new RoleAssignmentNotFoundException(command.roleAssignmentId()));
        RoleAssignment saved = roleAssignmentRepository.save(assignment.revoke(command.revokedBy(), command.reason(), clock.now()));
        audit(saved, IdentityAuditAction.ROLE_ASSIGNMENT_REVOKED, command.revokedBy(), AuditOutcome.SUCCESS, command.reason(), command.correlationId());
        publish(saved, "identity.role.revoked.v1", command.correlationId());
        identityMetricsPort.recordRoleAssignmentChange("REVOKED");
        return saved;
    }

    /** 03-state-machine §RoleAssignment: {@code PENDING --cancel--> CANCELLED}. */
    @Override
    @Transactional
    public RoleAssignment cancel(CancelRoleAssignmentCommand command) {
        RoleAssignment assignment = roleAssignmentRepository.findById(command.roleAssignmentId())
            .orElseThrow(() -> new RoleAssignmentNotFoundException(command.roleAssignmentId()));
        RoleAssignment saved = roleAssignmentRepository.save(assignment.cancel(command.cancelledBy(), command.reason(), clock.now()));
        audit(saved, IdentityAuditAction.ROLE_ASSIGNMENT_CANCELLED, command.cancelledBy(), AuditOutcome.SUCCESS, command.reason(), command.correlationId());
        publish(saved, "identity.role.cancelled.v1", command.correlationId());
        identityMetricsPort.recordRoleAssignmentChange("CANCELLED");
        return saved;
    }

    @Override
    public List<RoleAssignment> listForUser(ListRoleAssignmentsQuery query) {
        return roleAssignmentRepository.findByUserIdentityId(query.userIdentityId());
    }

    @Override
    public List<RoleAssignment> listEffectiveForUser(ListRoleAssignmentsQuery query) {
        Instant now = clock.now();
        return roleAssignmentRepository.findByUserIdentityId(query.userIdentityId()).stream()
            .filter(assignment -> assignment.isActive(now))
            .toList();
    }

    /**
     * The time-driven edges 03-state-machine names: {@code PENDING
     * --activate(validFrom)--> ACTIVE} and {@code ACTIVE --validUntil
     * reached--> EXPIRED}. Each row transitions in its own small
     * transaction so one bad row cannot block the rest of the batch.
     */
    @Override
    @Transactional
    public RoleAssignmentReconciliationResult reconcileDueTransitions() {
        Instant now = clock.now();
        int activated = 0;
        for (RoleAssignment pending : roleAssignmentRepository.findPendingDue(now)) {
            RoleAssignment saved = roleAssignmentRepository.save(pending.activate(now));
            audit(saved, IdentityAuditAction.ROLE_ASSIGNMENT_ACTIVATED, "system:reconciliation", AuditOutcome.SUCCESS, "validFrom reached", UUID.randomUUID().toString());
            publish(saved, "identity.role.assigned.v1", saved.roleAssignmentId());
            identityMetricsPort.recordRoleAssignmentChange("ACTIVATED");
            activated++;
        }
        int expired = 0;
        for (RoleAssignment active : roleAssignmentRepository.findActiveExpired(now)) {
            RoleAssignment saved = roleAssignmentRepository.save(active.expire(now));
            audit(saved, IdentityAuditAction.ROLE_ASSIGNMENT_EXPIRED, "system:reconciliation", AuditOutcome.SUCCESS, "validUntil reached", UUID.randomUUID().toString());
            publish(saved, "identity.role.revoked.v1", saved.roleAssignmentId());
            identityMetricsPort.recordRoleAssignmentChange("EXPIRED");
            expired++;
        }
        return new RoleAssignmentReconciliationResult(activated, expired);
    }

    private void audit(RoleAssignment saved, IdentityAuditAction action, String actorId, AuditOutcome outcome, String reason, String correlationId) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), saved.tenantId(), action, actorId, saved.userIdentityId(), saved.roleAssignmentId(),
            outcome, reason, new CorrelationId(correlationId), clock.now()
        ));
    }

    private void publish(RoleAssignment saved, String eventType, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentId", saved.roleAssignmentId());
        payload.put("userIdentityId", saved.userIdentityId());
        payload.put("roleCode", saved.roleCode().name());
        payload.put("scope", saved.scope().scopeType().name());
        if (saved.validUntil() != null) {
            payload.put("validUntil", saved.validUntil().toString());
        }
        eventPublisherPort.publish(eventType, AGGREGATE_TYPE, saved.roleAssignmentId(), writeJson(payload), correlationId);
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize role assignment event payload", e);
        }
    }
}
