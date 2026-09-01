# SPEC-ARO-037 — Test Plan

Goal: support `Conversational Intake Workflow Type`.

- Unit tests cover the new enum values being accepted by domain constructors and rejected when malformed.
- Unit tests cover deterministic resolution of the fixed `task_graph` template.
- Integration tests confirm real Postgres persists and reads back the new enum values correctly.
- Architecture/import-linter contracts remain unaffected (no new cross-package dependency introduced).
