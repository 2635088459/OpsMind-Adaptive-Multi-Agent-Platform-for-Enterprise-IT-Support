# Event Contract — SPEC-TG-012

## Event Principles

- Published events must go through `outbox_events`.
- Consumed events must write `processed_events(eventId, consumerName)`.
- Event payloads must not contain secrets or unredacted raw output.
- `correlationId` / `causationId` must flow through Runtime, Gateway, Policy, and Memory.

## Related Events

This spec may involve:

- `tool.request.accepted.v1`
- `tool.request.rejected.v1`
- `tool.approval.required.v1`
- `approval.granted.v1`
- `approval.denied.v1`
- `tool.execution.started.v1`
- `tool.execution.retry_scheduled.v1`
- `tool.completed.v1`
- `tool.connector.health_changed.v1`

Concrete payloads must align with `05-tool-integration-gateway/06-event-contracts`.
