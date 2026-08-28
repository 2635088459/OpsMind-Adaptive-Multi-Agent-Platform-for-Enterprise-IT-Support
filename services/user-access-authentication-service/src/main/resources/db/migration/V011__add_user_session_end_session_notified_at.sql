-- SPEC-UA-009 (04-use-cases §Logout/revocation: "request IdP end-session/
-- revocation"; 10-failure-handling: "Keep local revocation and retry if IdP
-- is unavailable"). Null until the best-effort Keycloak end-session
-- notification for this REVOKED session succeeds; reconciliation scans for
-- REVOKED rows where this is still null.
ALTER TABLE identity.user_sessions ADD COLUMN end_session_notified_at TIMESTAMPTZ;

CREATE INDEX ix_user_sessions_pending_end_session_notification
    ON identity.user_sessions (status)
    WHERE status = 'REVOKED' AND end_session_notified_at IS NULL;
