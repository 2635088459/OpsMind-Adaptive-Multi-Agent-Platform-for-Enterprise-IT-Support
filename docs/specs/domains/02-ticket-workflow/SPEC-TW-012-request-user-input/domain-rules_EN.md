# SPEC-TW-012 — Domain Rules

## 1. Status Transition

| Current | Target | Transition ID | Reason Code |
|---|---|---|---|
| `IN_PROGRESS` | `WAITING_FOR_USER` | `SM-014` | `USER_INPUT_REQUIRED` |

## 2. Invariants

- Ticket status is `IN_PROGRESS`;
- ticket has `current_support_user_id`;
- ticket has `current_resolution_cycle_id`;
- no open user input request exists for the ticket;
- prompt is requester-safe;
- assignee and current resolution cycle are retained;
- `waiting_for_requester_since = requested_at`;
- `approval_reference` is null or unchanged; user input request cannot masquerade as approval.

## 3. Resume Status

The current persistent ticket resume target is fixed:

```text
IN_PROGRESS
```

If workflow runtime resume hints are needed, store `resume_status = IN_PROGRESS` or `resume_context`, but clients cannot choose arbitrary workflow IDs.

## 4. Security

Prompt is externally visible content. It must be secret-filtered and must not ask requesters to submit passwords, MFA codes, access tokens, or private keys.
