# SPEC-EP-003 — Draft Preservation on Expiry

> Domain: `09-employee-portal` | Phase: 01 — Login and Session | Status: Implemented

## 1. Spec Identity
`SPEC-EP-003`, the direct implementation of BI-EP-006.

## 2. Objective
When session expiry interrupts an in-progress message, the typed-but-unsent text (and any pending attachment refs) is written to local storage and restored after a successful re-login — never silently lost.

## 3. Design References
`02-business-invariants` BI-EP-006; `07-data-model` §2.2 (`draft:{conversationId}`); `10-error-handling-and-reconciliation` §2.5.

## 4. Actor
A logged-in employee whose session expires mid-interaction.

## 5. Scope
Writing the draft to `localStorage` on a 401/`SESSION_EXPIRED` transition; restoring it after re-login; per-account key isolation on a shared device (`subject`-prefixed keys, per `07-data-model` §4).

## 6. Non-goals
Does not attempt to auto-resend the interrupted request — whether resending is safe is left to the user to decide (echoes `10-error-handling-and-reconciliation` §2.5).

## 7. Preconditions
A message is being composed or was just submitted when a 401 is received.

## 8. Input
The current composer text + any `READY`/`uploading` attachment references.

## 9. Detailed Behavior
On 401: write `draft:{subject}:{conversationId}` synchronously via `localStorage.setItem` (not an async IndexedDB write, per `08-transaction-and-outbox` §3) → prompt re-login → on success, read the draft back into the composer.

## 10. Interaction State Transition
Rides on SPEC-EP-002's `SESSION_EXPIRED` transition; adds no new state of its own.

## 11. Business Invariants
BI-EP-006 (this spec's own reason for existing).

## 12. Idempotency Strategy
Restoring the same draft twice (e.g. a duplicate re-login event) is idempotent — the draft key is simply overwritten/read, no duplication.

## 13. Consumed/Depended-on Contracts
None new — depends on SPEC-EP-001/002's session mechanism.

## 14. Security
The draft key is prefixed by `subject` to prevent cross-account leakage on a shared device/browser profile (`07-data-model` §4).

## 15. Observability
A metric for how often this path triggers is listed as a future dashboard input (`12-observability-and-audit` §4), not required for Definition of Done.

## 16. Error Scenarios
`localStorage` write fails (e.g. quota exceeded, private browsing) → the employee still sees the re-login prompt; losing the draft in this rare case is an acceptable degradation, not silently masked as success.

## 17. Acceptance Scenarios
A message typed but not yet sent survives a simulated session expiry and reappears in the composer after re-login.

## 18. Tests First
A component test simulating a 401 mid-composition, asserting the draft is written and restored; a cross-account isolation test on a shared browser profile.

## 19. Definition of Done
No typed-but-unsent draft is ever lost across a real session-expiry/re-login cycle, verified by an automated test, not just manual observation.
