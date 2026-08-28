package com.opsmind.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.SyncUserIdentityCommand;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.EventPublisherPort;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.query.FindUserIdentityByExternalSubjectQuery;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * UC-UA-001: resolve/link the trusted {@link UserIdentity} for a verified
 * {@code (issuer, subject)} and change its lifecycle status
 * (03-state-machine §UserIdentity). Because {@link
 * UserIdentityRepository#findByExternalSubject} is a natural identity key,
 * {@link #link} is idempotent by construction — repeating the same login
 * never creates a second {@link UserIdentity} (acceptance criteria:
 * "Duplicate commands ... produce no conflicting state or repeated side
 * effects"). Full claims normalization (SPEC-UA-007) and multi-IdP account
 * linking (SPEC-UA-008) build on this.
 *
 * <p>08-transaction-and-outbox: "provision/disable user: user identity +
 * audit + user event" — every transition commits state, audit, and an
 * outbox row together (SPEC-UA-003).
 */
@Service
public class ProvisionUserService implements ProvisionUserUseCase {

    private static final String AGGREGATE_TYPE = "UserIdentity";

    /**
     * SPEC-UA-031: 07-data-model names no concrete numeric retention window
     * for {@code user_identities} PII — the same gap SPEC-UA-019 hit for
     * break-glass grant TTL, resolved there by choosing and documenting a
     * bound rather than leaving retention unbounded. 90 days past {@code
     * deprovisionedAt} is chosen here for the same reason: long enough to
     * cover a routine post-offboarding audit/dispute window, short enough
     * that PII is not retained indefinitely once authority is gone.
     */
    static final Duration PII_RETENTION_PERIOD = Duration.ofDays(90);

    private final UserIdentityRepository userIdentityRepository;
    private final AuditPort auditPort;
    private final EventPublisherPort eventPublisherPort;
    private final ClockPort clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProvisionUserService(
        UserIdentityRepository userIdentityRepository, AuditPort auditPort, EventPublisherPort eventPublisherPort, ClockPort clock
    ) {
        this.userIdentityRepository = userIdentityRepository;
        this.auditPort = auditPort;
        this.eventPublisherPort = eventPublisherPort;
        this.clock = clock;
    }

    @Override
    @Transactional
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
        publish(saved, "identity.user.provisioned.v1", command.correlationId());
        return saved;
    }

    @Override
    @Transactional
    public UserIdentity sync(SyncUserIdentityCommand command) {
        UserIdentity current = findByIdOrThrow(command.userIdentityId());
        UserIdentity synced = current.sync(command.username(), command.displayName(), command.email(), command.profileVersion(), clock.now());
        UserIdentity saved = userIdentityRepository.save(synced);
        audit(saved.userIdentityId(), saved.tenantId(), IdentityAuditAction.USER_IDENTITY_SYNCED, AuditOutcome.SUCCESS, null, command.correlationId());
        return saved;
    }

    @Override
    @Transactional
    public UserIdentity changeStatus(ChangeUserIdentityStatusCommand command) {
        UserIdentity current = findByIdOrThrow(command.userIdentityId());
        UserStatus from = current.status();
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
        publishStatusChanged(saved, from, command.reason(), command.correlationId());
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

    @Override
    @Transactional
    public int reconcilePrivacyRetention() {
        Instant cutoff = clock.now().minus(PII_RETENTION_PERIOD);
        int count = 0;
        for (UserIdentity due : userIdentityRepository.findDeprovisionedDueForPiiRedaction(cutoff)) {
            UserIdentity saved = userIdentityRepository.save(due.redactPii(clock.now()));
            audit(saved.userIdentityId(), saved.tenantId(), IdentityAuditAction.USER_IDENTITY_PII_REDACTED, AuditOutcome.SUCCESS,
                "retention window elapsed", UUID.randomUUID().toString());
            count++;
        }
        return count;
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

    private void publish(UserIdentity saved, String eventType, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userIdentityId", saved.userIdentityId());
        payload.put("issuer", saved.externalSubject().issuer());
        payload.put("status", saved.status().name());
        writeAndPublish(saved.userIdentityId(), eventType, payload, correlationId);
    }

    private void publishStatusChanged(UserIdentity saved, UserStatus from, String reasonCode, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userIdentityId", saved.userIdentityId());
        payload.put("from", from.name());
        payload.put("to", saved.status().name());
        payload.put("reasonCode", reasonCode);
        writeAndPublish(saved.userIdentityId(), "identity.user.status.changed.v1", payload, correlationId);
    }

    private void writeAndPublish(String aggregateId, String eventType, Map<String, Object> payload, String correlationId) {
        try {
            eventPublisherPort.publish(eventType, AGGREGATE_TYPE, aggregateId, objectMapper.writeValueAsString(payload), correlationId);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize user identity event payload", e);
        }
    }
}
