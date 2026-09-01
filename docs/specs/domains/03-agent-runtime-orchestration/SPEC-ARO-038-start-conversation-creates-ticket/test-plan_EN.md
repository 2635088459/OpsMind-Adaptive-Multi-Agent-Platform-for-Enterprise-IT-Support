# SPEC-ARO-038 — Test Plan

Goal: support `Start Conversation Creates Ticket`.

- Integration test against the real docker-compose stack (both `agent-runtime-service` and `ticket-workflow-service` up together), asserting both real rows appear.
- Contract test for the outbound call shape against `02-ticket-workflow`'s real `POST /api/v1/tickets`.
- Failure test: `ticket-workflow-service` unavailable → this endpoint fails cleanly; no orphaned/partial `workflow_instances` row is left behind.
- Idempotency test: the same `Idempotency-Key` resubmitted concurrently produces exactly one ticket and one workflow instance.
