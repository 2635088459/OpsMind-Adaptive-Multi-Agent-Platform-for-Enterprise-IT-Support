-- SPEC-PG-015 (11-security §Separation Of Duties: "forbid ... tool
-- execution worker approving the corresponding tool request"): the
-- identity of the principal that will execute tool_request_id, if the
-- requesting domain (05 Tool Gateway) knows and supplies it. Nullable — 06
-- must not fabricate an executor identity it was never given.
ALTER TABLE governance.approval_requests
    ADD COLUMN executor_id VARCHAR(128);
