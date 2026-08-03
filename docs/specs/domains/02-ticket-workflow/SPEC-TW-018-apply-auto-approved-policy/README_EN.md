# SPEC-TW-018 — Apply Auto-Approved Policy

## 1. Goal

Consume trusted `policy.action-auto-approved.v1` or a local policy-adapter low-risk auto-approval result and store an explicit authorization reference for the pending action.

Auto-approved does not mean "no approval"; it means "explicitly approved by policy." Phase 05 stores authorization only and does not execute tools.

## 2. Scope

Included:

- policy event/adapter input;
- ticket, workflow, action, and risk context matching;
- store `AUTO_APPROVED` request/decision;
- publish `ticket.auto-approval-applied.v1`;
- duplicate/stale/wrong-producer classification.

Excluded: Tool Execution, policy editor, advanced risk model.
