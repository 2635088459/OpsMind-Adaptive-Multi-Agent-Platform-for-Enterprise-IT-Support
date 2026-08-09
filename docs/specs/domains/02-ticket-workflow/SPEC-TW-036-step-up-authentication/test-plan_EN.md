# SPEC-TW-036 Test Plan

## Unit Tests

- policy allow/deny decisions;
- fail-closed branches;
- redaction and low-cardinality telemetry;
- validation for missing actor/context/operation.

## Integration Tests

- integrate with at least two existing Phase 01 to Phase 08 endpoints;
- rejected paths do not write business-success outbox events;
- audit/metric/trace is recorded;
- policy bypass attempts fail.

## Regression Tests

- create/query/timeline/assignment/escalation/close golden paths still pass;
- error contracts do not leak internal policy details.
