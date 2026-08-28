package com.opsmind.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.command.LogoutCommand;
import com.opsmind.identity.application.command.RefreshSessionCommand;
import com.opsmind.identity.application.command.RevokeSessionCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.exception.TokenReplayDetectedException;
import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.exception.UserSessionNotFoundException;
import com.opsmind.identity.application.port.in.ManageSessionUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.EventPublisherPort;
import com.opsmind.identity.application.port.out.IdentityMetricsPort;
import com.opsmind.identity.application.port.out.OidcProviderPort;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.session.AuthenticationAssurance;
import com.opsmind.identity.domain.session.SessionStatus;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * UC-UA-005/UC-UA-007: start/revoke/refresh/logout {@link UserSession}s and
 * reconcile its time-driven expiry and best-effort IdP end-session
 * notification (02-business-invariants: "session-revocation metadata";
 * 03-state-machine §UserSession: {@code ACTIVE --expiry--> EXPIRED}). This
 * is the direct entry point to session issuance; SPEC-UA-005 (Authorization
 * Code PKCE Login Callback) adds the real OIDC login flow as another caller
 * of {@link #start}, not a replacement.
 *
 * <p>08-transaction-and-outbox: "revoke session: session + audit +
 * revocation event" — every local transition commits state, audit, and an
 * outbox row together (SPEC-UA-003). The real Keycloak end-session call
 * ({@link #reconcileEndSessionNotifications}) deliberately never runs
 * inside a database transaction (08-transaction-and-outbox: "Calls to
 * Keycloak, RabbitMQ, or another domain are forbidden inside the database
 * transaction") — {@link #revoke} and {@link #logout} only ever commit the
 * local revocation; notifying Keycloak is entirely reconciliation's job,
 * mirroring how {@code OutboxDispatchService} keeps its own external
 * publish call outside any DB transaction too.
 */
@Service
public class ManageSessionService implements ManageSessionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ManageSessionService.class);
    private static final String AGGREGATE_TYPE = "UserSession";

    private final UserSessionRepository sessionRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final AuditPort auditPort;
    private final EventPublisherPort eventPublisherPort;
    private final OidcProviderPort oidcProviderPort;
    private final IdentityMetricsPort identityMetricsPort;
    private final ClockPort clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManageSessionService(
        UserSessionRepository sessionRepository, UserIdentityRepository userIdentityRepository, AuditPort auditPort,
        EventPublisherPort eventPublisherPort, OidcProviderPort oidcProviderPort, IdentityMetricsPort identityMetricsPort, ClockPort clock
    ) {
        this.sessionRepository = sessionRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.auditPort = auditPort;
        this.eventPublisherPort = eventPublisherPort;
        this.oidcProviderPort = oidcProviderPort;
        this.identityMetricsPort = identityMetricsPort;
        this.clock = clock;
    }

    /**
     * INV-UA-002 (deny by default): refuses to start a session for a user
     * identity that is not {@code ACTIVE}.
     *
     * <p>SPEC-UA-034 (07-data-model §user_sessions: "UNIQUE session hash";
     * 11-security: "token substitution/replay/theft"): also refuses to
     * start a second session for a {@code tokenIdHash} that already backs
     * one — an application-level pre-check first (fails predictably even
     * outside a real race), with the real DB {@code
     * uq_user_sessions_token_id_hash} constraint as defense-in-depth for a
     * genuinely concurrent replay; either path converts to the same real
     * {@link TokenReplayDetectedException}, never the generic {@code
     * DataIntegrityViolationException}→409 handler, since this is a
     * genuine security signal, not an ordinary optimistic-concurrency
     * conflict.
     */
    @Override
    @Transactional
    public UserSession start(StartSessionCommand command) {
        ExternalSubject externalSubject = new ExternalSubject(command.issuer(), command.subject());
        UserIdentity user = userIdentityRepository.findByExternalSubject(command.tenantId(), externalSubject)
            .orElseThrow(() -> new UserIdentityNotFoundException(externalSubject.subject()));
        if (!user.isActive()) {
            auditPort.record(IdentityAuditRecord.record(
                UUID.randomUUID().toString(), user.tenantId(), IdentityAuditAction.USER_SESSION_STARTED, null,
                user.userIdentityId(), null, AuditOutcome.DENIED, "user identity status is " + user.status(),
                new CorrelationId(command.correlationId()), clock.now()
            ));
            throw new UserIdentityNotEligibleException(user.userIdentityId());
        }
        if (command.tokenIdHash() != null && sessionRepository.existsByTokenIdHash(command.tokenIdHash())) {
            denyTokenReplay(user, command.correlationId());
        }

        Instant now = clock.now();
        AuthenticationAssurance assurance = new AuthenticationAssurance(command.acr(), command.amr(), command.authTime());
        UserSession session = UserSession.start(
            UUID.randomUUID().toString(), user.tenantId(), externalSubject, command.idpSessionIdHash(), command.tokenIdHash(),
            command.clientId(), assurance, command.deviceIdHash(), now, now.plus(command.ttl())
        );
        UserSession saved;
        try {
            saved = sessionRepository.save(session);
        } catch (DataIntegrityViolationException e) {
            denyTokenReplay(user, command.correlationId());
            throw e; // unreachable — denyTokenReplay always throws — but keeps the compiler honest.
        }
        audit(saved, IdentityAuditAction.USER_SESSION_STARTED, null, AuditOutcome.SUCCESS, null, command.correlationId());
        identityMetricsPort.recordSessionLifecycle("STARTED");
        return saved;
    }

    private void denyTokenReplay(UserIdentity user, String correlationId) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), user.tenantId(), IdentityAuditAction.USER_SESSION_STARTED, null,
            user.userIdentityId(), null, AuditOutcome.DENIED, "token replay detected",
            new CorrelationId(correlationId), clock.now()
        ));
        throw new TokenReplayDetectedException();
    }

    /** 09-concurrency-and-idempotency: "Session revocation is idempotent" — a retry against an already-{@code REVOKED} session returns the existing state. */
    @Override
    @Transactional
    public UserSession revoke(RevokeSessionCommand command) {
        UserSession session = findByIdOrThrow(command.userSessionId());
        if (session.status() == SessionStatus.REVOKED) {
            return session;
        }
        UserSession saved = sessionRepository.save(session.revoke(command.revokedBy(), command.reason(), clock.now()));
        audit(saved, IdentityAuditAction.USER_SESSION_REVOKED, command.revokedBy(), AuditOutcome.SUCCESS, command.reason(), command.correlationId());
        publish(saved, "identity.session.revoked.v1", command.correlationId());
        identityMetricsPort.recordSessionLifecycle("REVOKED");
        return saved;
    }

    /**
     * SPEC-UA-009 (05-api-contracts {@code POST /sessions/logout}: "Session
     * derived from principal"). Silently no-ops when no matching session is
     * found, or when the one found belongs to a different subject than the
     * verified caller (05-api-contracts: "Errors do not distinguish
     * nonexistent users from unauthorized visibility") — logout is
     * idempotent and never discloses session existence either way.
     */
    @Override
    @Transactional
    public void logout(LogoutCommand command) {
        Optional<UserSession> found = sessionRepository.findActiveByIdpSessionIdHash(command.idpSessionIdHash());
        if (found.isEmpty()) {
            return;
        }
        UserSession session = found.get();
        ExternalSubject callerSubject = new ExternalSubject(command.issuer(), command.subject());
        if (!session.tenantId().value().equals(command.tenantId()) || !session.externalSubject().equals(callerSubject)) {
            return;
        }
        UserSession saved = sessionRepository.save(session.revoke(command.subject(), "self-service logout", clock.now()));
        audit(saved, IdentityAuditAction.USER_SESSION_REVOKED, command.subject(), AuditOutcome.SUCCESS, "self-service logout", command.correlationId());
        publish(saved, "identity.session.revoked.v1", command.correlationId());
        identityMetricsPort.recordSessionLifecycle("REVOKED");
    }

    /** SPEC-UA-009 ("Session Refresh"): {@code UserSession#touch}'s own javadoc — never undoes revocation, since it requires {@code ACTIVE}. */
    @Override
    @Transactional
    public UserSession refresh(RefreshSessionCommand command) {
        UserSession session = findByIdOrThrow(command.userSessionId());
        return sessionRepository.save(session.touch(clock.now()));
    }

    @Override
    public UserSession findById(String userSessionId) {
        return findByIdOrThrow(userSessionId);
    }

    /** 03-state-machine §UserSession: {@code ACTIVE --expiry--> EXPIRED} — admin/scheduler-triggered. */
    @Override
    @Transactional
    public int reconcileExpired() {
        Instant now = clock.now();
        int count = 0;
        for (UserSession active : sessionRepository.findActiveExpired(now)) {
            UserSession saved = sessionRepository.save(active.expire(now));
            audit(saved, IdentityAuditAction.USER_SESSION_EXPIRED, "system:reconciliation", AuditOutcome.SUCCESS, "expiry reached", UUID.randomUUID().toString());
            identityMetricsPort.recordSessionLifecycle("EXPIRED");
            count++;
        }
        return count;
    }

    /**
     * SPEC-UA-009 (10-failure-handling: "Keep local revocation and retry if
     * IdP is unavailable"). Deliberately not {@code @Transactional} — see
     * this class's own javadoc for why the external call must sit outside
     * any database transaction; each successful notification's own {@code
     * save()} still commits atomically via Spring Data JPA's own per-call
     * transaction.
     */
    @Override
    public int reconcileEndSessionNotifications() {
        int count = 0;
        for (UserSession revoked : sessionRepository.findRevokedPendingEndSessionNotification()) {
            try {
                oidcProviderPort.requestEndSession(revoked.externalSubject());
                sessionRepository.save(revoked.markEndSessionNotified(clock.now()));
                count++;
            } catch (Exception e) {
                log.warn("failed to notify IdP of end-session for session {}, will retry: {}", revoked.userSessionId(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * SPEC-UA-033 (10-failure-handling: "Delayed revocation event | ... |
     * Reconciliation scan"). Scans the bounded set of currently {@code
     * ACTIVE} sessions (not every ever-deactivated identity — a much
     * larger, unbounded set) and checks each one's own owning identity
     * directly via the same real {@link UserIdentityRepository#findByExternalSubject}
     * {@link #start} already uses; a missing or non-{@code ACTIVE} identity
     * means this session's own authority is stale and revokes it through
     * the exact same real {@link UserSession#revoke} transition {@link
     * #revoke} uses — {@link #reconcileEndSessionNotifications} picks up
     * the resulting REVOKED-but-unnotified session automatically on its
     * own next pass, with no new wiring needed here.
     */
    @Override
    @Transactional
    public int reconcileForInactiveIdentities() {
        int count = 0;
        for (UserSession active : sessionRepository.findAllActive()) {
            Optional<UserIdentity> owner = userIdentityRepository.findByExternalSubject(active.tenantId().value(), active.externalSubject());
            boolean ownerStillActive = owner.isPresent() && owner.get().isActive();
            if (!ownerStillActive) {
                UserSession saved = sessionRepository.save(active.revoke("system:reconciliation", "user identity is no longer ACTIVE", clock.now()));
                audit(saved, IdentityAuditAction.USER_SESSION_REVOKED, "system:reconciliation", AuditOutcome.SUCCESS, "user identity is no longer ACTIVE", UUID.randomUUID().toString());
                publish(saved, "identity.session.revoked.v1", UUID.randomUUID().toString());
                identityMetricsPort.recordSessionLifecycle("REVOKED");
                count++;
            }
        }
        return count;
    }

    private UserSession findByIdOrThrow(String userSessionId) {
        return sessionRepository.findById(userSessionId)
            .orElseThrow(() -> new UserSessionNotFoundException(userSessionId));
    }

    private void audit(UserSession saved, IdentityAuditAction action, String actorId, AuditOutcome outcome, String reason, String correlationId) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), saved.tenantId(), action, actorId, saved.externalSubject().subject(), saved.userSessionId(),
            outcome, reason, new CorrelationId(correlationId), clock.now()
        ));
    }

    private void publish(UserSession saved, String eventType, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", saved.userSessionId());
        payload.put("subjectRef", saved.externalSubject().subject());
        payload.put("reasonCode", saved.revocationReason());
        payload.put("revokedAt", saved.revokedAt() == null ? null : saved.revokedAt().toString());
        try {
            eventPublisherPort.publish(eventType, AGGREGATE_TYPE, saved.userSessionId(), objectMapper.writeValueAsString(payload), correlationId);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize user session event payload", e);
        }
    }
}
