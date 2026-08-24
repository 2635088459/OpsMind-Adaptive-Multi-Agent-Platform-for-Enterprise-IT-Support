package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.SyncUserIdentityCommand;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.query.FindUserIdentityByExternalSubjectQuery;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * UC-UA-001: resolve/link the trusted {@link UserIdentity} for a verified
 * {@code (issuer, subject)}. Because {@link UserIdentityRepository#findByExternalSubject}
 * is a natural identity key, {@link #link} is idempotent by construction —
 * repeating the same login never creates a second {@link UserIdentity}
 * (acceptance criteria: "Duplicate commands ... produce no conflicting
 * state or repeated side effects"). Full claims normalization (SPEC-UA-007)
 * and multi-IdP account linking (SPEC-UA-008) build on this.
 */
@Service
public class ProvisionUserService implements ProvisionUserUseCase {

    private final UserIdentityRepository userIdentityRepository;
    private final AuditPort auditPort;
    private final ClockPort clock;

    public ProvisionUserService(UserIdentityRepository userIdentityRepository, AuditPort auditPort, ClockPort clock) {
        this.userIdentityRepository = userIdentityRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    @Override
    public UserIdentity link(LinkUserIdentityCommand command) {
        TenantId tenantId = new TenantId(command.tenantId());
        ExternalSubject externalSubject = new ExternalSubject(command.issuer(), command.subject());
        Optional<UserIdentity> existing = userIdentityRepository.findByExternalSubject(command.tenantId(), externalSubject);
        if (existing.isPresent()) {
            return existing.get();
        }

        UserIdentity linked = UserIdentity.link(
            UUID.randomUUID().toString(), tenantId, externalSubject, command.username(), command.displayName(),
            command.email(), command.identityType(), clock.now()
        );
        UserIdentity saved = userIdentityRepository.save(linked);
        audit(saved.userIdentityId(), tenantId, IdentityAuditAction.USER_IDENTITY_LINKED, AuditOutcome.SUCCESS, "first sight of issuer+subject", command.correlationId());
        return saved;
    }

    @Override
    public UserIdentity sync(SyncUserIdentityCommand command) {
        UserIdentity current = findByIdOrThrow(command.userIdentityId());
        UserIdentity synced = current.sync(command.username(), command.displayName(), command.email(), command.profileVersion(), clock.now());
        UserIdentity saved = userIdentityRepository.save(synced);
        audit(saved.userIdentityId(), saved.tenantId(), IdentityAuditAction.USER_IDENTITY_SYNCED, AuditOutcome.SUCCESS, null, command.correlationId());
        return saved;
    }

    @Override
    public UserIdentity changeStatus(ChangeUserIdentityStatusCommand command) {
        UserIdentity current = findByIdOrThrow(command.userIdentityId());
        UserIdentity updated = switch (command.targetStatus()) {
            case ACTIVE -> current.enable(clock.now());
            case DISABLED -> current.disable(clock.now());
            case DEPROVISIONED -> current.deprovision(clock.now());
        };
        UserIdentity saved = userIdentityRepository.save(updated);
        IdentityAuditAction action = switch (command.targetStatus()) {
            case ACTIVE -> IdentityAuditAction.USER_IDENTITY_ENABLED;
            case DISABLED -> IdentityAuditAction.USER_IDENTITY_DISABLED;
            case DEPROVISIONED -> IdentityAuditAction.USER_IDENTITY_DEPROVISIONED;
        };
        audit(saved.userIdentityId(), saved.tenantId(), action, AuditOutcome.SUCCESS, command.reason(), command.correlationId());
        return saved;
    }

    @Override
    public UserIdentity findById(String userIdentityId) {
        return findByIdOrThrow(userIdentityId);
    }

    @Override
    public UserIdentity findByExternalSubject(FindUserIdentityByExternalSubjectQuery query) {
        return userIdentityRepository.findByExternalSubject(query.tenantId(), query.externalSubject())
            .orElseThrow(() -> new UserIdentityNotFoundException(query.externalSubject().subject()));
    }

    private UserIdentity findByIdOrThrow(String userIdentityId) {
        return userIdentityRepository.findById(userIdentityId)
            .orElseThrow(() -> new UserIdentityNotFoundException(userIdentityId));
    }

    private void audit(String subjectRef, TenantId tenantId, IdentityAuditAction action, AuditOutcome outcome, String reason, String correlationId) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), tenantId, action, null, subjectRef, null, outcome, reason, new CorrelationId(correlationId), clock.now()
        ));
    }
}
