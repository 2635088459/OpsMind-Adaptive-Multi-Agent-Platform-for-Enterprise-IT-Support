# SPEC-ARO-039 — Domain Rules

Goal: support `Inline Message Turn Execution`.

- A `process_user_message` task follows the same `AgentTask` domain model as every other task type, but its state transitions happen synchronously within the request rather than via `claim`/`complete`.
- Knowledge-base content retrieved from `04-memory-knowledge` is used to inform the reply, never fabricated when retrieval fails — a retrieval failure degrades to a plainer answer or an escalation, never a hallucinated "citation."
- The checkpoint written for this turn is a genuine, recoverable snapshot (per `01-domain-model`'s existing checkpoint requirements), not a placeholder.
