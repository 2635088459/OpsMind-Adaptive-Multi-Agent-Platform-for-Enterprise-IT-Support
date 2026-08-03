# SPEC-TW-014 — Request Approval

## 1. Goal

Request approval for a high-risk pending action and move the ticket from `IN_PROGRESS` to `WAITING_FOR_APPROVAL`.

A successful command stores the pending action, approval reference, risk context, requestedBy/requestedAt, and writes timeline, audit, status history, outbox, and idempotency response.

## 2. Scope

Included:

- `POST /api/v1/tickets/{ticketId}/approval-requests`
- `IN_PROGRESS -> WAITING_FOR_APPROVAL`
- pending action reference
- approval reference
- risk context snapshot
- `ticket.approval-wait-started.v1`

Excluded: real Approval Service, approval UI, and tool execution.

## 3. Core Rules

- ticket status is `IN_PROGRESS`;
- ticket has assignee and support queue;
- pending action has stable `actionId`, `actionType`, and `workflowId`;
- at most one open approval request exists per ticket;
- approval binds ticket, workflow, action, and risk context;
- clients cannot spoof approver or approval decision.

## 4. File Index

This directory contains bilingual design docs, OpenAPI, AsyncAPI, HTTP examples, and reference migration.
