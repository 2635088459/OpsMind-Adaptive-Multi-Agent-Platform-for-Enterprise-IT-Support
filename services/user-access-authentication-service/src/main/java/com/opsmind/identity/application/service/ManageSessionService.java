package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.RevokeSessionCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.exception.UserSessionNotFoundException;
import com.opsmind.identity.application.port.in.ManageSessionUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * UC-UA-005/UC-UA-007: start/revoke {@link UserSession}s
 * (02-business-invariants: "session-revocation metadata"). This is the
 * direct entry point to session issuance; SPEC-UA-005 (Authorization Code
 * PKCE Login Callback) adds the real OIDC login flow as another caller of
 * {@link #start}, not a replacement.
 */
@Service
public class ManageSessionService implements ManageSessionUseCase {

    private final UserSessionRepository sessionRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final AuditPort auditPort;
    private final ClockPort clock;

    public ManageSessionService(
        UserSessionRepository sessionRepository, UserIdentityRepository userIdentityRepository, AuditPort auditPort, ClockPort clock
    ) {
        this.sessionRepository = sessionRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    /** INV-UA-002 (deny by default): refuses to start a session for a user identity that is not {@code ACTIVE}. */
    @Override
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

        Instant now = clock.now();
        AuthenticationAssurance assurance = new AuthenticationAssurance(command.acr(), command.amr(), command.authTime());
        UserSession session = UserSession.start(
            UUID.randomUUID().toString(), user.tenantId(), externalSubject, command.idpSessionIdHash(), command.tokenIdHash(),
            command.clientId(), assurance, command.deviceIdHash(), now, now.plus(command.ttl())
        );
        UserSession saved = sessionRepository.save(session);
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), user.tenantId(), IdentityAuditAction.USER_SESSION_STARTED, null,
            user.userIdentityId(), saved.userSessionId(), AuditOutcome.SUCCESS, null,
            new CorrelationId(command.correlationId()), clock.now()
        ));
        return saved;
    }

    /** 09-concurrency-and-idempotency: "Session revocation is idempotent" — a retry against an already-{@code REVOKED} session returns the existing state. */
    @Override
    public UserSession revoke(RevokeSessionCommand command) {
        UserSession session = findByIdOrThrow(command.userSessionId());
        if (session.status() == SessionStatus.REVOKED) {
            return session;
        }
        UserSession saved = sessionRepository.save(session.revoke(command.revokedBy(), command.reason(), clock.now()));
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), saved.tenantId(), IdentityAuditAction.USER_SESSION_REVOKED, command.revokedBy(),
            saved.externalSubject().subject(), saved.userSessionId(), AuditOutcome.SUCCESS, command.reason(),
            new CorrelationId(command.correlationId()), clock.now()
        ));
        return saved;
    }

    @Override
    public UserSession findById(String userSessionId) {
        return findByIdOrThrow(userSessionId);
    }

    private UserSession findByIdOrThrow(String userSessionId) {
        return sessionRepository.findById(userSessionId)
            .orElseThrow(() -> new UserSessionNotFoundException(userSessionId));
    }
}
