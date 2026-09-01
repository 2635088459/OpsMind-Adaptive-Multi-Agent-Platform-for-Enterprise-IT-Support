# SPEC-ARO-037 — API Contract

Goal: support `Conversational Intake Workflow Type`.

- This spec introduces no HTTP endpoint of its own — it is a definitional/enum spec.
- Its output (the fixed `task_graph` template and the enum values) is read by SPEC-ARO-038's and SPEC-ARO-039's own endpoints.
- No existing endpoint's request/response shape changes.
