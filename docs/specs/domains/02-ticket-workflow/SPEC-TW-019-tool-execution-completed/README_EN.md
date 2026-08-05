# SPEC-TW-019 — Tool Execution Completed

## 1. Goal

Consume trusted `tool.execution.completed.v1`, verify the result matches the current ticket, workflow, action, authorization reference, and toolExecutionId, then move the ticket from `EXECUTING` to `VERIFYING`.

Tool success means the operation completed, not that the issue is solved. Resolve waits for Phase 07 Verification.

## 2. Scope

Included:

- Tool Gateway event consumer;
- producer/schema validation;
- `EXECUTING -> VERIFYING`;
- tool result reference;
- verification seed;
- duplicate/stale classification;
- timeline, audit, status history, outbox.

Excluded:

- Tool Gateway invocation;
- credential acquisition;
- Verification execution;
- Resolve.

## 3. Core Rules

- Ticket status is `EXECUTING`;
- event matches the current action;
- `toolExecutionId` is unique;
- success never enters `RESOLVED` directly;
- duplicate event replay does not create another verification attempt;
- event before authorization or from old workflow is stale/DLQ.

## 4. File Index

This directory contains bilingual design docs, OpenAPI, AsyncAPI, HTTP examples, and reference migration.
