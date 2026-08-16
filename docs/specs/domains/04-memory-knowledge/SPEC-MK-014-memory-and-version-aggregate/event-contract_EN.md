# SPEC-MK-014 Event Contract

## Event Scope

- Consumed events must use the shared envelope and deduplicate by `eventId + consumerName`.
- Published events must go through `memory.outbox_events`.
- Event payloads must include sufficient provenance or source refs.

## Relationship With 02/03

- Ticket events from 02 are fact inputs only.
- Workflow events from 03 are automation trace/evidence inputs only.
- Memory events published to 03/07 must not require downstream direct Ticket/Workflow mutation.
