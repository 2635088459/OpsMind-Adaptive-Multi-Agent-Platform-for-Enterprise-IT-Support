package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.RevokeRoleAssignmentCommand;
import com.opsmind.identity.application.exception.RoleAssignmentNotFoundException;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UC-UA-004: grant/revoke a {@link RoleAssignment}. {@link #grant} is
 * idempotent while a matching {@code ACTIVE} assignment already exists
 * (acceptance criteria: "Duplicate commands ... produce no conflicting
 * state"). "A role grantor cannot delegate beyond its own grant scope"
 * (02-business-invariants #9) and the fuller lifecycle (scheduled/delegated
 * grants, overlap validation beyond the natural-key check here) are
 * SPEC-UA-012's job.
 */
@Service
public class ManageRoleAssignmentService implements ManageRoleAssignmentUseCase {

    private final RoleAssignmentRepository roleAssignmentRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final AuditPort auditPort;
    private final ClockPort clock;

    public ManageRoleAssignmentService(
        RoleAssignmentRepository roleAssignmentRepository, UserIdentityRepository userIdentityRepository,
        AuditPort auditPort, ClockPort clock
    ) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    @Override
    public RoleAssignment grant(GrantRoleAssignmentCommand command) {
        UserIdentity user = userIdentityRepository.findById(command.userIdentityId())
            .orElseThrow(() -> new UserIdentityNotFoundException(command.userIdentityId()));

        Optional<RoleAssignment> existing = roleAssignmentRepository.findActive(
            command.userIdentityId(), command.roleCode(), command.scope(), clock.now()
        );
        if (existing.isPresent()) {
            return existing.get();
        }

        RoleAssignment assignment = RoleAssignment.grantActive(
            UUID.randomUUID().toString(), new TenantId(command.tenantId()), command.userIdentityId(), command.roleCode(),
            command.scope(), command.permissions(), command.validUntil(), command.grantedBy(), command.grantReason(), clock.now()
        );
        RoleAssignment saved = roleAssignmentRepository.save(assignment);
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), user.tenantId(), IdentityAuditAction.ROLE_ASSIGNMENT_GRANTED, command.grantedBy(),
            saved.userIdentityId(), saved.roleAssignmentId(), AuditOutcome.SUCCESS, "role=" + saved.roleCode(),
            new CorrelationId(command.correlationId()), clock.now()
        ));
        return saved;
    }

    @Override
    public RoleAssignment revoke(RevokeRoleAssignmentCommand command) {
        RoleAssignment assignment = roleAssignmentRepository.findById(command.roleAssignmentId())
            .orElseThrow(() -> new RoleAssignmentNotFoundException(command.roleAssignmentId()));
        RoleAssignment saved = roleAssignmentRepository.save(assignment.revoke(command.revokedBy(), command.reason(), clock.now()));
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), saved.tenantId(), IdentityAuditAction.ROLE_ASSIGNMENT_REVOKED, command.revokedBy(),
            saved.userIdentityId(), saved.roleAssignmentId(), AuditOutcome.SUCCESS, command.reason(),
            new CorrelationId(command.correlationId()), clock.now()
        ));
        return saved;
    }

    @Override
    public List<RoleAssignment> listForUser(ListRoleAssignmentsQuery query) {
        return roleAssignmentRepository.findByUserIdentityId(query.userIdentityId());
    }
}
