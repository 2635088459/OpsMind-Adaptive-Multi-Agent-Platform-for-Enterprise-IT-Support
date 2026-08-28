package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.session.UserSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository {

    Optional<UserSession> findById(String userSessionId);

    /** 03-state-machine: {@code ACTIVE} sessions past their own {@code expiresAt} — due for {@link UserSession#expire}. */
    List<UserSession> findActiveExpired(Instant now);

    /** SPEC-UA-009 (self-service {@code POST /sessions/logout}): the caller's own current session, resolved from the {@code sid} claim hash on their own verified token — never an arbitrary caller-supplied session id. */
    Optional<UserSession> findActiveByIdpSessionIdHash(String idpSessionIdHash);

    /** SPEC-UA-009 (10-failure-handling: "Keep local revocation and retry if IdP is unavailable"): {@code REVOKED} sessions whose best-effort IdP end-session notification has not yet succeeded. */
    List<UserSession> findRevokedPendingEndSessionNotification();

    /** SPEC-UA-033: every currently {@code ACTIVE} session — the bounded scan set {@link com.opsmind.identity.application.service.ManageSessionService#reconcileForInactiveIdentities} checks against each owning {@code UserIdentity}'s own current status. */
    List<UserSession> findAllActive();

    /** SPEC-UA-034 (07-data-model §user_sessions: "UNIQUE session hash"; 11-security: "token substitution/replay/theft"). {@code true} if any session — regardless of status — already exists for this {@code tokenIdHash}, meaning the same bearer token is being replayed to start a second session. */
    boolean existsByTokenIdHash(String tokenIdHash);

    UserSession save(UserSession session);
}
