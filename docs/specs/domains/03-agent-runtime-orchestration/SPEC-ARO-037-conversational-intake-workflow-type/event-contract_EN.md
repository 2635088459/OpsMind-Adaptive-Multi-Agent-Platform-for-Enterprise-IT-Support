# SPEC-ARO-037 — Event Contract

Goal: support `Conversational Intake Workflow Type`.

- No new published or consumed event. This spec only adds enum values to an existing aggregate.
- Existing event contracts (`ticket.created.v1` consumption, workflow-lifecycle outbox publication) are entirely unaffected — they already treat `workflow_type` as an opaque string field.
