# SPEC-TW-007 — Acceptance Criteria

## AC-01 — Successful Triage

**Given** an existing ticket in `OPEN`, an active category, optional valid subcategory, active support queue, authorized actor, unused idempotency key, and matching `If-Match` version  
**When** the actor submits the triage command  
**Then** the service returns `200 OK`, sets the triage fields, changes status to `TRIAGED`, increments the version, returns the new `ETag`, and atomically writes history, timeline, audit, idempotency result, and `ticket.triaged.v1`.

## AC-02 — Optional Subcategory

**Given** a valid category and no `subcategoryId`  
**When** triage succeeds  
**Then** `subcategoryId` remains `null`.

## AC-03 — Category Validation

The service returns `422 TRIAGE_CATEGORY_INVALID` when the category does not exist or is inactive. No mutation is committed.

## AC-04 — Subcategory Relationship

The service returns `422 TRIAGE_SUBCATEGORY_INVALID` when the subcategory does not exist, is inactive, or does not belong to the selected category.

## AC-05 — Priority Validation

Only `LOW`, `MEDIUM`, `HIGH`, and `CRITICAL` are accepted. Any other value returns `400 VALIDATION_ERROR`.

## AC-06 — Queue Validation

The service returns `422 SUPPORT_QUEUE_INVALID` when the queue does not exist or is inactive.

## AC-07 — Queue Authorization

An authenticated actor without triage permission for the target queue receives `403 QUEUE_ACCESS_DENIED`. A Requester always receives `403 TRIAGE_NOT_ALLOWED`.

## AC-08 — State Guard

Only `OPEN` may be triaged. A ticket in any other state returns `409 INVALID_TICKET_STATE` with `currentStatus` and `requiredStatus=OPEN`.

## AC-09 — Not Found and Tenant Isolation

An unknown ticket, or a ticket outside the actor's tenant, returns `404 TICKET_NOT_FOUND`. The response must not reveal whether a cross-tenant ticket exists.

## AC-10 — Optimistic Locking

A missing `If-Match` returns `428 PRECONDITION_REQUIRED`. A stale version returns `412 VERSION_CONFLICT`, includes the current `ETag`, and commits no mutation.

## AC-11 — Idempotent Replay

The same actor, route, idempotency key, and canonical request hash return the previously stored `200` response and `ETag`. No second history, timeline, audit, or outbox record is created.

## AC-12 — Idempotency Conflict

Reusing an idempotency key with a different request hash returns `409 IDEMPOTENCY_KEY_REUSED`.

## AC-13 — Actor Integrity

`triagedBy` is taken from the access token/service identity. Any actor field in the body is rejected as an unknown property.

## AC-14 — Atomic Rollback

If any required persistence operation fails, the ticket and all side records remain unchanged. No publishable outbox record exists.

## AC-15 — Event and Audit Safety

The event, log, and audit payloads contain identifiers and approved business fields only. They must not contain access tokens, credentials, or unrestricted requester message content.

## AC-16 — Observability

Every attempt records `ticket_triage_total` with a bounded result label and command duration. Structured logs include `ticketId`, `actorId`, `fromStatus`, `toStatus`, `result`, `errorCode`, and `correlationId`.

## Definition of Done

- all ACs have automated tests;
- `openapi.yaml` and implementation contract tests agree;
- `asyncapi.yaml` and serialized event contract tests agree;
- migration succeeds on an empty supported database and the Phase 02 schema;
- rollback and two-writer concurrency tests pass;
- no critical/high security findings;
- documentation and code use the same field names and error codes.

