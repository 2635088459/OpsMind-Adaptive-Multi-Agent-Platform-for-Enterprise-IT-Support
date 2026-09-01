# SPEC-ARO-042 — Test Plan

Goal: support `Resume Conversation Query`.

- Integration test: query by subject returns the correct, most recent conversation among several belonging to the same employee.
- Security test: a JWT belonging to a different employee cannot retrieve another employee's conversation, by id or by "most recent" query.
- Unit test: the response-shape adapter over `WorkflowQueryPort` produces the exact conversation view shape domain 09 expects.
