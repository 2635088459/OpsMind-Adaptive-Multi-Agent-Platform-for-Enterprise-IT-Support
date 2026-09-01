# SPEC-ARO-042 — API Contract

Goal: support `Resume Conversation Query`.

- `GET /api/v1/conversations/{conversationId}` → maps to the existing `WorkflowQueryPort.find(workflowInstanceId)`, response reshaped to the conversation view.
- `GET /api/v1/conversations:mostRecent` (or an equivalent query-by-identity shape decided during implementation) → resolves the calling employee's identity from the JWT, returns their most recent active/escalated conversation, or a `404`/empty result if none exists.
- Possible new column need flagged: if `workflow_instances` has no existing "created-by subject" field to query against, one may need to be added — left to implementation to confirm against the real schema.
