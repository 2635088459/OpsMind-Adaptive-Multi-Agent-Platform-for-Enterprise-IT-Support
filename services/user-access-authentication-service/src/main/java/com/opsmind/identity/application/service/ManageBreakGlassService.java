package com.opsmind.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.command.ActivateBreakGlassCommand;
import com.opsmind.identity.application.command.ReconcileApprovalOutcomeCommand;
import com.opsmind.identity.application.command.RevokeBreakGlassCommand;
import com.opsmind.identity.application.exception.BreakGlassActivationDeniedException;
import com.opsmind.identity.application.exception.BreakGlassGrantNotFoundException;
import com.opsmind.identity.application.exception.IdpUnavailableException;
import com.opsmind.identity.application.port.in.ManageBreakGlassUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.BreakGlassGrantRepository;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.EventPublisherPort;
import com.opsmind.identity.application.port.out.OidcProviderPort;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.breakglass.ApprovalOutcome;
import com.opsmind.identity.domain.breakglass.BreakGlassGrant;
import com.opsmind.identity.domain.breakglass.BreakGlassStatus;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPEC-UA-019 (Break Glass And Account Recovery — 04-use-cases §Break-glass:
 * "Authorized admin | Strong authentication + dual/06 approval + bounded
 * time/scope | Auto-expire and emit high-priority audit"; 11-security:
 * "requires strong authentication, domain-06 approval/dual control, bounded
 * scope/time, and non-disableable audit").
 *
 * <p>No 07-data-model table or 01-domain-model aggregate was ever named for
 * break-glass anywhere in this domain's own LLD — unlike every prior
 * SPEC-UA-0xx spec, there is no already-built-but-unwired scaffold to close
 * here; {@link BreakGlassGrant} is this spec's own from-scratch design,
 * following the same conventions every other aggregate already uses.
 *
 * <p>{@code approvalReference} (domain 06's own already-decided approval/
 * dual-control fact) is only ever stored and audited, never independently
 * validated — 02-business-invariants #8: "Domain 01 decides identity-level
 * access and domain 06 decides risk, approval, and business governance."
 * 06-event-contracts names a real consumed fact ("Domain 06: approval or
 * break-glass approved/denied/expired facts") but its own footer maps only
 * to SPEC-UA-028/029, never this spec — so the real RabbitMQ consumer that
 * would let domain 01 verify an approval reference automatically is
 * deliberately left to those specs, the same "IdP-event consumer is a later
 * spec's job" split this domain has used consistently since SPEC-UA-008.
 *
 * <p>"Account Recovery" (the other half of this spec's own name) is already
 * real: {@code ProvisionUserUseCase#changeStatus}'s own {@code
 * DISABLED -> ACTIVE} reactivation path has existed since SPEC-UA-001,
 * gated by SPEC-UA-011's own {@code identity:user:admin} permission — no
 * new capability was needed there.
 *
 * <p>"Non-disableable audit" is already true by construction for every
 * write path in this domain (INV-UA-006, no audit-bypass mechanism exists
 * anywhere in this codebase) — {@link #activate} and {@link #revoke} simply
 * follow the same unconditional audit-every-transition discipline as every
 * other aggregate.
 *
 * <p>SPEC-UA-029 (Identity Security Audit Events — 12-observability's own
 * "Alerts cover ... break-glass use"): {@code identity.security.alert.v1}
 * (06-event-contracts §Published events) had zero real producer anywhere
 * in this codebase until now. Wired onto the two most security-significant
 * BreakGlassGrant transitions only — {@link #activate} itself (the literal
 * "break-glass use") and a {@link #reconcileApprovalOutcome}-triggered
 * revoke (a grant had to be retroactively pulled because the approval that
 * justified it was denied/expired after the fact) — deliberately NOT
 * every {@link #revoke}/{@link #reconcileExpired} transition, since a
 * routine admin-initiated revoke or an ordinary bounded-time expiry is not
 * itself an alert-worthy signal the way activation or a
 * denied/expired-approval correction is.
 */
@Service
public class ManageBreakGlassService implements ManageBreakGlassUseCase {

    /**
     * 11-security only says "bounded scope/time" without naming a concrete
     * value anywhere in this domain's own LLD — a deliberate, documented
     * interpretation (not a fabricated one, since some bound must exist by
     * construction per {@link BreakGlassGrant}'s own compact constructor),
     * matching common emergency-access practice.
     */
    static final Duration MAX_TTL = Duration.ofHours(4);

    private final BreakGlassGrantRepository breakGlassGrantRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final UserSessionRepository userSessionRepository;
    private final AuditPort auditPort;
    private final EventPublisherPort eventPublisherPort;
    private final OidcProviderPort oidcProviderPort;
    private final ClockPort clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManageBreakGlassService(
        BreakGlassGrantRepository breakGlassGrantRepository, UserIdentityRepository userIdentityRepository,
        UserSessionRepository userSessionRepository, AuditPort auditPort, EventPublisherPort eventPublisherPort,
        OidcProviderPort oidcProviderPort, ClockPort clock
    ) {
        this.breakGlassGrantRepository = breakGlassGrantRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.userSessionRepository = userSessionRepository;
        this.auditPort = auditPort;
        this.eventPublisherPort = eventPublisherPort;
        this.oidcProviderPort = oidcProviderPort;
        this.clock = clock;
    }

    /**
     * SPEC-UA-032 (10-failure-handling: "Keycloak unavailable ... sensitive
     * actions return 503/fail closed"; 02-business-invariants #12:
     * "Sensitive operations deny when ... IdP trust fails"). Checked first,
     * before any of {@link #deny}'s own precondition checks — creating
     * MORE emergency access is the single most sensitive thing this domain
     * does, so it is the last operation that should proceed on unconfirmed
     * IdP trust. Deliberately its own {@link IdpUnavailableException}
     * (503, retryable) rather than {@link #deny}'s {@link
     * BreakGlassActivationDeniedException} (403) — this is a transient
     * infrastructure condition, not a permanent lack of authority.
     */
    @Override
    @Transactional
    public BreakGlassGrant activate(ActivateBreakGlassCommand command) {
        ExternalSubject externalSubject = new ExternalSubject(command.issuer(), command.subject());
        TenantId tenantId = new TenantId(command.tenantId());
        Instant now = clock.now();

        if (!oidcProviderPort.isAvailable()) {
            auditPort.record(IdentityAuditRecord.record(
                UUID.randomUUID().toString(), tenantId, IdentityAuditAction.BREAK_GLASS_ACTIVATION_DENIED, externalSubject.subject(),
                externalSubject.subject(), null, AuditOutcome.DENIED, "IdP availability could not be confirmed",
                new CorrelationId(command.correlationId()), now
            ));
            throw new IdpUnavailableException("break-glass activation");
        }

        Optional<UserIdentity> user = userIdentityRepository.findByExternalSubject(command.tenantId(), externalSubject);
        if (user.isEmpty() || !user.get().isActive()) {
            deny(tenantId, externalSubject, command.correlationId(), "no active user identity for this subject");
        }

        if (command.approvalReference() == null || command.approvalReference().isBlank()) {
            deny(tenantId, externalSubject, command.correlationId(), "a domain-06 approval reference is required");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            deny(tenantId, externalSubject, command.correlationId(), "a reason is required");
        }
        if (command.ttl() == null || command.ttl().isNegative() || command.ttl().isZero() || command.ttl().compareTo(MAX_TTL) > 0) {
            deny(tenantId, externalSubject, command.correlationId(), "requested duration is not within the bounded maximum");
        }

        UserSession session = command.sessionId() == null ? null : userSessionRepository.findById(command.sessionId()).orElse(null);
        if (session == null || !session.isValid(now) || !session.externalSubject().equals(externalSubject)) {
            deny(tenantId, externalSubject, command.correlationId(), "no valid session for this subject to strongly authenticate from");
        }
        if (!session.assurance().satisfies(command.requiredAssuranceLevel(), command.requiredAssuranceMethods())) {
            deny(tenantId, externalSubject, command.correlationId(), "the session does not meet the required strong-authentication level");
        }

        BreakGlassGrant grant = BreakGlassGrant.activate(
            UUID.randomUUID().toString(), tenantId, externalSubject, command.scope(), command.approvalReference(), command.reason(),
            command.subject(), now, now.plus(command.ttl()), command.correlationId()
        );
        BreakGlassGrant saved = breakGlassGrantRepository.save(grant);
        audit(saved, IdentityAuditAction.BREAK_GLASS_ACTIVATED, AuditOutcome.SUCCESS, "reason=" + saved.reason(), command.correlationId());
        publishSecurityAlert(saved, "BREAK_GLASS_ACTIVATED", "HIGH", command.sessionId(), null, command.correlationId());
        return saved;
    }

    @Override
    @Transactional
    public BreakGlassGrant revoke(RevokeBreakGlassCommand command) {
        BreakGlassGrant grant = findByIdOrThrow(command.breakGlassGrantId());
        BreakGlassGrant saved = breakGlassGrantRepository.save(grant.revoke(command.revokedBy(), command.reason(), clock.now()));
        audit(saved, IdentityAuditAction.BREAK_GLASS_REVOKED, AuditOutcome.SUCCESS, command.reason(), command.correlationId());
        return saved;
    }

    @Override
    public BreakGlassGrant findById(String breakGlassGrantId) {
        return findByIdOrThrow(breakGlassGrantId);
    }

    /** 04-use-cases §Break-glass: "Auto-expire" — admin/scheduler-triggered, mirroring every other time-bounded aggregate's own reconciliation. */
    @Override
    @Transactional
    public int reconcileExpired() {
        Instant now = clock.now();
        int count = 0;
        for (BreakGlassGrant active : breakGlassGrantRepository.findActiveExpired(now)) {
            BreakGlassGrant saved = breakGlassGrantRepository.save(active.expire(now));
            audit(saved, IdentityAuditAction.BREAK_GLASS_EXPIRED, AuditOutcome.SUCCESS, "bounded time reached", UUID.randomUUID().toString());
            count++;
        }
        return count;
    }

    /**
     * SPEC-UA-028: the async, independent verification UA-019's own {@code
     * approvalReference} was always missing. GRANTED is a no-op (already
     * trusted at activation time); DENIED/EXPIRED revokes any still-ACTIVE
     * grant that referenced this approval — reuses {@link #revoke}'s own
     * real transition/audit rather than duplicating it. {@code
     * findByApprovalReference} is not unique, so every matching grant (not
     * just the first) is reconciled — a caller could in principle activate
     * more than one break-glass grant off the same approval reference.
     */
    @Override
    @Transactional
    public void reconcileApprovalOutcome(ReconcileApprovalOutcomeCommand command) {
        if (command.outcome() == ApprovalOutcome.GRANTED) {
            return;
        }
        String reason = "the underlying domain-06 approval was " + command.outcome().name().toLowerCase(java.util.Locale.ROOT);
        for (BreakGlassGrant grant : breakGlassGrantRepository.findByApprovalReference(command.approvalRequestId())) {
            if (grant.status() == BreakGlassStatus.ACTIVE) {
                BreakGlassGrant revoked = revoke(new RevokeBreakGlassCommand(grant.breakGlassGrantId(), "system:approval-reconciliation", reason, command.correlationId()));
                publishSecurityAlert(revoked, "BREAK_GLASS_REVOKED_AFTER_APPROVAL_OUTCOME", "HIGH", null, command.outcome().name(), command.correlationId());
            }
        }
    }

    private BreakGlassGrant findByIdOrThrow(String breakGlassGrantId) {
        return breakGlassGrantRepository.findById(breakGlassGrantId)
            .orElseThrow(() -> new BreakGlassGrantNotFoundException(breakGlassGrantId));
    }

    private void deny(TenantId tenantId, ExternalSubject externalSubject, String correlationId, String reason) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), tenantId, IdentityAuditAction.BREAK_GLASS_ACTIVATION_DENIED, externalSubject.subject(),
            externalSubject.subject(), null, AuditOutcome.DENIED, reason, new CorrelationId(correlationId), clock.now()
        ));
        throw new BreakGlassActivationDeniedException(reason);
    }

    private void audit(BreakGlassGrant grant, IdentityAuditAction action, AuditOutcome outcome, String reason, String correlationId) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), grant.tenantId(), action, grant.externalSubject().subject(), grant.externalSubject().subject(),
            grant.breakGlassGrantId(), outcome, reason, new CorrelationId(correlationId), clock.now()
        ));
    }

    /**
     * SPEC-UA-029: {@code identity.security.alert.v1}
     * (06-event-contracts §Published events: "alertType, severity,
     * subjectRef, sessionRef, reasonCode") — real subject/session
     * references only, never raw claims or tokens (11-security's own
     * "Subjects use opaque IDs/hashes" discipline, already established by
     * every other event this domain publishes).
     */
    private void publishSecurityAlert(BreakGlassGrant grant, String alertType, String severity, String sessionRef, String reasonCode, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertType", alertType);
        payload.put("severity", severity);
        payload.put("subjectRef", grant.externalSubject().subject());
        payload.put("sessionRef", sessionRef);
        payload.put("reasonCode", reasonCode);
        try {
            eventPublisherPort.publish(
                "identity.security.alert.v1", "BreakGlassGrant", grant.breakGlassGrantId(), objectMapper.writeValueAsString(payload), correlationId
            );
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize identity.security.alert.v1 payload", e);
        }
    }
}
