# SPEC-ARO-042 — Domain Rules

Goal: support `Resume Conversation Query`.

- Reads follow the existing `WorkflowQueryPort` read-consistency guarantees; this spec adds no new consistency model.
- "Most recent" is ordered by real workflow-instance creation/update timestamps already persisted — no new derived-freshness concept is introduced.
