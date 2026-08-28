package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.LogoutCommand;
import com.opsmind.identity.application.command.RefreshSessionCommand;
import com.opsmind.identity.application.command.RevokeSessionCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.domain.session.UserSession;

/** 13-package-and-class-design §Primary Input Ports; 04-use-cases §OIDC login, §Logout/revocation. */
public interface ManageSessionUseCase {

    UserSession start(StartSessionCommand command);

    UserSession revoke(RevokeSessionCommand command);

    /** SPEC-UA-009 (05-api-contracts {@code POST /sessions/logout}): revokes the caller's own current session, resolved from their own verified token, idempotently and without ever disclosing whether a matching session existed. */
    void logout(LogoutCommand command);

    /** SPEC-UA-009 ("Session Refresh"): updates {@code lastSeenAt} for an {@code ACTIVE} session — see {@code UserSession#touch}'s own javadoc for why this can never undo a revocation. */
    UserSession refresh(RefreshSessionCommand command);

    UserSession findById(String userSessionId);

    /** 03-state-machine: {@code ACTIVE --expiry--> EXPIRED} — admin/scheduler-triggered, see {@code OutboxDispatchService}'s own "nothing calls this automatically" convention. */
    int reconcileExpired();

    /** SPEC-UA-009 (10-failure-handling: "Keep local revocation and retry if IdP is unavailable") — admin/scheduler-triggered retry of the best-effort Keycloak end-session notification. */
    int reconcileEndSessionNotifications();

    /**
     * SPEC-UA-033 (10-failure-handling: "Delayed revocation event | ... |
     * Reconciliation scan"; 02-business-invariants #12: "Revocation
     * propagates with eventual consistency"). A {@code DISABLED}/{@code
     * DEPROVISIONED} user identity does not itself revoke that user's own
     * still-{@code ACTIVE} sessions — this closes that gap, admin/scheduler-triggered
     * like every other reconciliation in this domain. Revoked sessions
     * flow into {@link #reconcileEndSessionNotifications}'s own existing
     * scan automatically, since revocation here uses the exact same {@code
     * UserSession#revoke} transition.
     */
    int reconcileForInactiveIdentities();
}
