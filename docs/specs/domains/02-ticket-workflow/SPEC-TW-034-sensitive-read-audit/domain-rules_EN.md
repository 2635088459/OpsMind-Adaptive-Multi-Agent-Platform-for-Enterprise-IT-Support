# SPEC-TW-034 Domain Rules

- This SPEC is Phase 09 hardening and introduces no primary Ticket lifecycle state.
- Sensitive details must not be returned when required audit persistence fails.
- Policy runs before mutation; read paths run policy before sensitive fields are materialized.
- Fail-closed behavior cannot be bypassed by fallback, retry, or partial response.
- Every policy decision leaves an auditable decision code.
