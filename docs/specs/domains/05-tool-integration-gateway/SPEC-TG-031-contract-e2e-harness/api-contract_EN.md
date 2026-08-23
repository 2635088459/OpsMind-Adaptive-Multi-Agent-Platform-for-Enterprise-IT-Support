# API Contract — SPEC-TG-031

## API Impact

This spec may add or modify Tool Gateway APIs, but must preserve:

- Runtime-facing APIs expose only capability, request, status, and redacted result;
- Admin-facing APIs require RBAC, audit reason, and correlation id;
- Raw-output or credential APIs are not callable by Agent/Runtime by default;
- Every command API must support idempotency key or version protection.

## Main Contract

- Input must include `correlationId` or allow Gateway to create and return one.
- Responses must not contain secrets, vault refs, or unredacted raw output.
- Errors must use stable error codes.
- Conflict, denied, timeout, and uncertain outcome must remain distinguishable.
