-- SPEC-PG-016 (11-security §Approval Authenticity): "Approval command must
-- include: authenticated actor; session/device metadata; idempotency key;
-- reason; optional MFA/step-up marker; correlation id." session_id/device_id
-- are nullable (06 has no session store of its own, so it only captures
-- what the caller reported); step_up_verified defaults false so an absent
-- marker is never silently treated as verified.
ALTER TABLE governance.approval_decisions
    ADD COLUMN session_id VARCHAR(200),
    ADD COLUMN device_id VARCHAR(200),
    ADD COLUMN step_up_verified BOOLEAN NOT NULL DEFAULT false;
