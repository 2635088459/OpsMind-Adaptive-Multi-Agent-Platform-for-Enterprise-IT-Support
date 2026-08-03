# SPEC-TW-015 — Persistence Design

Real migration: `V021__apply_approval_granted.sql`.

Persist:

- approval request `request_status = GRANTED`;
- `approved_by`, `approved_at`;
- `authorization_reference`;
- consumed event id;
- ticket `status = IN_PROGRESS`;
- clear `approval_reference` or mark it consumed.
