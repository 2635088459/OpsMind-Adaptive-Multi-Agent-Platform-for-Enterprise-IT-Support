# SPEC-EP-010 — Attachment Upload

> Domain: `09-employee-portal` | Phase: 04 — Evidence File Slice | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-010`, implements the attachment half of `UC-EP-02`.

## 2. Objective
Let an employee attach a photo/file to a message, driving the attachment state machine (`03-state-machine` §3.2) through to `READY`.

## 3. Design References
`01-domain-model` §"Attachment"; `03-state-machine` §3.2; `05-api-contracts` §3.

## 4. Actor
An employee composing a message.

## 5. Scope
The file-picker affordance, upload progress UI, and the `useUploadAttachment` hook.

## 6. Non-goals
Client-side file-type/size validation itself (SPEC-EP-011); the shared attachments capability's own backend implementation (chartered separately, not owned by this domain).

## 7. Preconditions
Turn state is `IDLE` (an attachment can be staged before sending).

## 8. Input
A file selected by the employee.

## 9. Detailed Behavior
Select file → `VALIDATING` (SPEC-EP-011) → `UPLOADING` → `READY`/`FAILED`, per `03-state-machine` §3.2.

## 10. Interaction State Transition
The full attachment state machine in `03-state-machine` §3.2.

## 11. Business Invariants
BI-EP-002 (this spec's own reason for existing) — only `READY` attachments may be referenced by SPEC-EP-005's send call.

## 12. Idempotency Strategy
A retried upload of the same file uses a fresh attempt (not an idempotency key in the HTTP sense) — a failed upload's retry is a new upload call, not a replay.

## 13. Consumed/Depended-on Contracts
`POST /api/v1/attachments` (the new independent shared capability, not yet designed — MSW-mocked for this spec's own tests).

## 14. Security
Client-side validation is explicitly not the security boundary (`11-security-and-authorization` §3) — real enforcement belongs to the shared capability's own server-side design.

## 15. Observability
An upload-success-rate metric is a future dashboard input (`12-observability-and-audit` §4).

## 16. Error Scenarios
Upload failure → `FAILED`, retryable or removable, never blocking the rest of the message (`10-error-handling-and-reconciliation` §2.2).

## 17. Acceptance Scenarios
A selected image progresses through `VALIDATING → UPLOADING → READY` and becomes referenceable by a subsequent send.

## 18. Tests First
A component/hook test for each attachment-state transition against the MSW mock.

## 19. Definition of Done
The full attachment state machine passes tests against the mock; a compatibility test is added once the shared attachments capability is real.
