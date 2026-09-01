# SPEC-ARO-038 — Event Contract

Goal: support `Start Conversation Creates Ticket`.

- No event is published by this spec directly. `02-ticket-workflow` publishes its own real `ticket.created.v1` as it always does — this spec's outbound call does not bypass or duplicate that.
- This spec does not itself consume `ticket.created.v1` — it creates the `WorkflowInstance` directly via an internal command, since the `ticketId` is already known synchronously (see README §2).
