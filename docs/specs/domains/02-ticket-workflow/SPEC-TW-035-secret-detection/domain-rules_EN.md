# SPEC-TW-035 Domain Rules

- This SPEC is Phase 09 hardening and introduces no primary Ticket lifecycle state.
- Free text classified as secret-like must be rejected, metered with redaction, and never persisted raw.
- Policy runs before mutation; read paths run policy before sensitive fields are materialized.
- Fail-closed behavior cannot be bypassed by fallback, retry, or partial response.
- Every policy decision leaves an auditable decision code.
