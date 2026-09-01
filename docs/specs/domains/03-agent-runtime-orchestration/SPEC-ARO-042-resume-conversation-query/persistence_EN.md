# SPEC-ARO-042 — Persistence Design

Goal: support `Resume Conversation Query`.

- No new table. Reads only from existing `workflow_instances`.
- If no "created-by subject" field currently exists on `workflow_instances`, a migration adding it (nullable, backfilled for new rows going forward) may be required — confirmed against the real schema during implementation, not assumed here.
- A supporting index on (subject, updated_at) may be needed for the "most recent" query's performance — left to implementation.
