# SPEC-TW-018 — Acceptance Criteria

- Low-risk action can be auto-approved by an explicit policy decision.
- Policy decision binds ticket, workflow, action, and risk context.
- Ticket remains `IN_PROGRESS`.
- Authorization reference is stored.
- Duplicate is idempotent.
- Stale/wrong-producer/schema-invalid classification is correct.
- Missing policy match cannot silently approve.
