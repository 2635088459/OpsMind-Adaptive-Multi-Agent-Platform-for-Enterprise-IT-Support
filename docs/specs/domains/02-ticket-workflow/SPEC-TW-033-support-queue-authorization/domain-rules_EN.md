# SPEC-TW-033 Domain Rules

- This SPEC is Phase 09 hardening and introduces no primary Ticket lifecycle state.
- Any queue-scoped actor can only read or mutate Tickets inside their authorized Support Queue scope.
- Policy runs before mutation; read paths run policy before sensitive fields are materialized.
- Fail-closed behavior cannot be bypassed by fallback, retry, or partial response.
- Every policy decision leaves an auditable decision code.
