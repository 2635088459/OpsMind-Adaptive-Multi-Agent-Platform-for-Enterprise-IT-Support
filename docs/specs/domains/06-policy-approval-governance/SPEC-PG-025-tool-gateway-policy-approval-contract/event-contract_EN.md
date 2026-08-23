# Event Contract — SPEC-PG-025

## Event Principles

- Published events must go through `outbox_events`.
- Consumed events must write `processed_events(eventId, consumerName)`.
- Events must carry source linkage, correlationId, and causationId.
- Event payloads must not contain sensitive raw input or secrets.

## Related Events

This spec may involve:

- `policy.decision.created.v1`
- `approval.requested.v1`
- `approval.granted.v1`
- `approval.denied.v1`
- `approval.expired.v1`
- `approval.cancelled.v1`
- `policy.published.v1`
- `policy.rule.changed.v1`
- `tool.approval.required.v1`
- `workflow.approval.required.v1`
- `ticket.approval.required.v1`

Concrete payloads must align with `06-policy-approval-governance/06-event-contracts`.
