# SPEC-TW-036 Domain Rules

- This SPEC is Phase 09 hardening and introduces no primary Ticket lifecycle state.
- High-risk commands without valid step-up proof must be rejected before business mutation.
- Policy runs before mutation; read paths run policy before sensitive fields are materialized.
- Fail-closed behavior cannot be bypassed by fallback, retry, or partial response.
- Every policy decision leaves an auditable decision code.
