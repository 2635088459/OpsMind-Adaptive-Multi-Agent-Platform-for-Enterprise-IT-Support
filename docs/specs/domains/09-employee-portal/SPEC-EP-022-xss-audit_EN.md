# SPEC-EP-022 — XSS Audit

> Domain: `09-employee-portal` | Phase: 08 — Security and Release Hardening | Status: Implemented

## 1. Spec Identity
`SPEC-EP-022`, a security-hardening pass specific to this domain's unusual risk surface: rendering LLM-authored and user-uploaded content.

## 2. Objective
Confirm no agent-authored message content, escalation notice text, or attachment filename can execute script in the employee's browser — the highest-risk surface in this entire domain, since agent output is inherently less trusted than typical backend-validated data.

## 3. Design References
`11-security-and-authorization` §4 (content-rendering security).

## 4. Actor
N/A — a security audit activity.

## 5. Scope
Every place agent message content, escalation text, or attachment filenames are rendered (SPEC-EP-005, SPEC-EP-007, SPEC-EP-012, and the attachment list from SPEC-EP-010).

## 6. Non-goals
Server-side sanitization (owned by the respective backend domains) — this spec verifies the frontend's own rendering layer never trusts raw HTML from any of these sources, as defense in depth regardless of backend behavior.

## 7. Preconditions
The relevant specs are implemented.

## 8. Input
Adversarial test fixtures: message content containing `<script>`/event-handler payloads, filenames containing HTML/script-like strings.

## 9. Detailed Behavior
Confirm all agent/escalation text is rendered as plain text (or through a markdown renderer with script execution disabled) never via `dangerouslySetInnerHTML` on unsanitized input; confirm attachment filenames are rendered as text nodes, never interpolated into HTML/URLs unescaped.

## 10. Interaction State Transition
N/A.

## 11. Business Invariants
A new cross-cutting invariant: no data originating from an LLM, another user, or a file's own metadata is ever rendered as executable HTML/script.

## 12. Idempotency Strategy
N/A.

## 13. Consumed/Depended-on Contracts
N/A — a rendering-layer audit, not a new contract consumer.

## 14. Security
This spec's entire content is a security concern; explicitly the single highest-priority spec in domain 09's security phase given the chat-with-an-LLM product surface.

## 15. Observability
N/A.

## 16. Error Scenarios
Any XSS vector found is a finding, tracked and fixed before this spec closes — not deferred.

## 17. Acceptance Scenarios
A message fixture containing `<img src=x onerror=alert(1)>` renders as inert text everywhere it could appear in the UI.

## 18. Tests First
A component test suite rendering every adversarial fixture through every content-rendering surface (SPEC-EP-005, 007, 010, 012), asserting no script execution and no unescaped HTML injection.

## 19. Definition of Done
All adversarial fixtures pass with zero script execution across every rendering surface in the app.
