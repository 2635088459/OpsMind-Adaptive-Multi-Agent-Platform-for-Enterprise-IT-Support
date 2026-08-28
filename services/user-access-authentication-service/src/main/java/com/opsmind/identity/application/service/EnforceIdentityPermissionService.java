package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.RequireIdentityPermissionCommand;
import com.opsmind.identity.application.command.RequireRoleGrantWithinScopeCommand;
import com.opsmind.identity.application.exception.PermissionDeniedException;
import com.opsmind.identity.application.exception.RoleGrantOverreachException;
import com.opsmind.identity.application.port.in.EnforceIdentityPermissionUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.role.RolePermissionCatalog;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SPEC-UA-011 (Role And Permission Model — {@code SecurityConfig}'s own
 * javadoc: "Per-endpoint RBAC/ABAC beyond 'authenticated' is
 * SPEC-UA-011/012/014's"). Resolves the caller's own {@code UserIdentity}
 * from its already-verified JWT (issuer/subject, never a request body field)
 * and denies (02-business-invariants #5, deny by default) unless one of its
 * currently-active {@link com.opsmind.identity.domain.role.RoleAssignment}s
 * grants the required permission — the same server-side-authoritative
 * permission set {@link com.opsmind.identity.domain.role.RolePermissionCatalog}
 * assigned at grant time, never a client-supplied one.
 */
@Service
public class EnforceIdentityPermissionService implements EnforceIdentityPermissionUseCase {

    private final UserIdentityRepository userIdentityRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final AuditPort auditPort;
    private final ClockPort clock;

    public EnforceIdentityPermissionService(
        UserIdentityRepository userIdentityRepository, RoleAssignmentRepository roleAssignmentRepository, AuditPort auditPort, ClockPort clock
    ) {
        this.userIdentityRepository = userIdentityRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    @Override
    public void require(RequireIdentityPermissionCommand command) {
        ExternalSubject externalSubject = new ExternalSubject(command.issuer(), command.subject());
        Instant now = clock.now();

        Optional<UserIdentity> found = userIdentityRepository.findByExternalSubject(command.tenantId(), externalSubject);
        if (found.isEmpty() || !found.get().isActive()) {
            deny(command);
        }
        UserIdentity user = found.get();

        boolean granted = roleAssignmentRepository.findByUserIdentityId(user.userIdentityId()).stream()
            .filter(assignment -> assignment.isActive(now))
            .anyMatch(assignment -> assignment.permissions().contains(command.requiredPermission()));
        if (!granted) {
            deny(command);
        }
    }

    private void deny(RequireIdentityPermissionCommand command) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), new TenantId(command.tenantId()), IdentityAuditAction.PERMISSION_DENIED, command.subject(),
            command.subject(), null, AuditOutcome.DENIED, "missing " + command.requiredPermission(),
            new CorrelationId(command.correlationId()), clock.now()
        ));
        throw new PermissionDeniedException(command.subject(), command.requiredPermission());
    }

    /**
     * SPEC-UA-012 (02-business-invariants #9: "A role grantor cannot
     * delegate beyond its own grant scope"). Fails closed (denies) whenever
     * the grantor cannot be resolved at all — the same deny-by-default
     * posture as {@link #require} — then requires the target role's own
     * {@link RolePermissionCatalog} permission set to be fully covered by
     * the grantor's own currently-active permissions; granting a role that
     * hands out a permission the grantor does not itself hold is exactly
     * the "delegate beyond its own grant scope" 02-business-invariants #9
     * forbids.
     */
    @Override
    public void requireGrantWithinScope(RequireRoleGrantWithinScopeCommand command) {
        ExternalSubject externalSubject = new ExternalSubject(command.issuer(), command.subject());
        Instant now = clock.now();

        Optional<UserIdentity> found = userIdentityRepository.findByExternalSubject(command.tenantId(), externalSubject);
        if (found.isEmpty() || !found.get().isActive()) {
            denyOverreach(command);
        }
        UserIdentity grantor = found.get();

        Set<String> grantorPermissions = roleAssignmentRepository.findByUserIdentityId(grantor.userIdentityId()).stream()
            .filter(assignment -> assignment.isActive(now))
            .flatMap(assignment -> assignment.permissions().stream())
            .collect(Collectors.toSet());
        Set<String> targetPermissions = RolePermissionCatalog.permissionsFor(command.targetRoleCode());
        if (!grantorPermissions.containsAll(targetPermissions)) {
            denyOverreach(command);
        }
    }

    private void denyOverreach(RequireRoleGrantWithinScopeCommand command) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), new TenantId(command.tenantId()), IdentityAuditAction.PERMISSION_DENIED, command.subject(),
            command.subject(), null, AuditOutcome.DENIED, "role grant exceeds grantor's own scope: target=" + command.targetRoleCode(),
            new CorrelationId(command.correlationId()), clock.now()
        ));
        throw new RoleGrantOverreachException(command.subject(), command.targetRoleCode());
    }
}
