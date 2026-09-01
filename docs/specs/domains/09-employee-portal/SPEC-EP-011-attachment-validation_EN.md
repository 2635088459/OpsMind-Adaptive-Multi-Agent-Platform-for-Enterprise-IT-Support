# SPEC-EP-011 — Attachment Validation

> Domain: `09-employee-portal` | Phase: 04 — Evidence File Slice | Status: Spec Planning

## 1. Spec Identity
`SPEC-EP-011`, the `VALIDATING` state's own behavior in `03-state-machine` §3.2.

## 2. Objective
Reject an obviously-unusable file (wrong type, over size limit) client-side, before spending an upload round-trip on it.

## 3. Design References
`03-state-machine` §3.2; `01-domain-model` §"Attachment" (allowed MIME types / max size, sourced from the shared attachments capability's own contract).

## 4. Actor
An employee selecting a file.

## 5. Scope
The `VALIDATING → UPLOADING`/`FAILED` decision, driven purely by client-visible file metadata (name, MIME type, byte size).

## 6. Non-goals
Content-level validation (e.g., actual image decodability, malware scanning) — that is the shared capability's own server-side concern, never duplicated client-side.

## 7. Preconditions
A file has just been selected (turn state `IDLE`).

## 8. Input
The selected `File` object's `type` and `size`.

## 9. Detailed Behavior
Check MIME type against an allow-list and size against a max — pass → `UPLOADING`; fail → `FAILED` with a specific reason (wrong type vs. too large), never a generic error.

## 10. Interaction State Transition
`03-state-machine` §3.2's `VALIDATING` node and its two outward edges.

## 11. Business Invariants
BI-EP-002 — an attachment that fails validation must never reach `READY` and must never be silently dropped without employee-visible feedback.

## 12. Idempotency Strategy
N/A — purely local, synchronous computation, no network call.

## 13. Consumed/Depended-on Contracts
None over the network; the allow-list/size-limit values themselves are sourced from the shared attachments capability's published contract (a config value, not an API call).

## 14. Security
This client-side check is explicitly advisory only (`11-security-and-authorization` §3) — never treated as the security boundary.

## 15. Observability
A validation-rejection reason is logged client-side for UX-quality tracking, not sent as a security signal.

## 16. Error Scenarios
Wrong file type → `FAILED` with "unsupported file type"; oversized file → `FAILED` with "file too large"; both distinctly worded per BI-EP-002.

## 17. Acceptance Scenarios
A `.exe` file is rejected with an unsupported-type message; a 500MB image is rejected with a too-large message; a valid `.png` under the limit proceeds to `UPLOADING`.

## 18. Tests First
Unit tests for the validation function covering both rejection reasons and the pass-through case.

## 19. Definition of Done
All three cases (wrong type, too large, valid) are covered by unit tests with distinct, employee-visible messaging.
