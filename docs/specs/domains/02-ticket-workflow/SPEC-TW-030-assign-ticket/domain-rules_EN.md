# SPEC-TW-030 Domain Rules

- Allowed source state: `mutable non-terminal states`.
- Target effect: `same lifecycle state`.
- Command actor: support lead, router, or assignment policy.
- Ticket mutations must pass state machine guards; controllers cannot update state directly.
- Commands record correlationId, causationId, idempotencyKey, actorId, reasonCode, and free-text reason.
- The outbox event and aggregate mutation commit in the same transaction.
- Assignment is an ownership mutation with its own audit version and must not rewrite resolution evidence.
