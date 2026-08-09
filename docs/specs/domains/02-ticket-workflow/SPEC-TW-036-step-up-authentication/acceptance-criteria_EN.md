# SPEC-TW-036 Acceptance Criteria

## Functional Acceptance

- Given a policy-compliant request, the existing Phase 01 to Phase 08 behavior remains unchanged.
- Given a policy-violating request, the system rejects before business mutation and returns a stable error contract.
- The `security.step-up-verified` audit/security record is written or metered.

## Security Acceptance

- Rejection responses do not leak authorization scope, detection rules, secret patterns, or internal policy details.
- Logs, metrics, and trace attributes stay low-cardinality and free of PII/secrets.
- Policy bypass tests cover controller, application service, and internal consumer entry points.

## Regression Acceptance

- Phase 01 to Phase 08 golden paths remain intact.
- Existing idempotency, outbox, and audit transaction boundaries remain unchanged.
