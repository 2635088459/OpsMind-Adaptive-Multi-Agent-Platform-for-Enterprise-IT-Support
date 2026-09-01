# SPEC-ARO-041 — Event Contract

Goal: support `Escalation Via Existing Triage`.

- No new event. Reuses `02-ticket-workflow`'s own already-real `ticket.triaged` event, unaffected by this spec.
- This spec's own outbound call is a synchronous HTTP request, not an event publication.
