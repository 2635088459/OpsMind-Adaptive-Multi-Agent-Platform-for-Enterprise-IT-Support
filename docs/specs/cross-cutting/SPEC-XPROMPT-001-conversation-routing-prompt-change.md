# SPEC-XPROMPT-001 — conversation routing prompt: "open a ticket for me" fix

Status: implemented 2026-09-10
Owner: platform / agent-runtime reasoning
Related: [[SPEC-XEVAL-001]] (the benchmark that measured every step), domain 03 (agent-runtime conversational intake, SPEC-ARO-037~043)
Scope: one string — `agentruntime.infrastructure.conversation_reasoning._SYSTEM_PROMPT` — plus 4 evaluation cases.

---

## 1. Trigger

A live chat (screenshot, 2026-09-10):

> **Employee:** "since I need access to log into StarRez, help me open a ticket to let IT team give me the access"
>
> **Agent:** "I can't open the ticket for you from here, but here's how to request StarRez access so IT can grant your role: – Use the Housing access request form … – Get the required signatures/approvals … Do you already have the access request form and approvals, or would you like help drafting the business justification text?"

Expected: the employee asked the agent to open a ticket → the agent should have **opened the ticket** (created a support ticket for a human), not explained the manual request process.

## 2. Root cause — a routing-policy gap, not a bug

The agent **can** open tickets. The conversational-intake turn resolves to exactly one
of three outcomes (`ConversationDecision.kind`):

| kind | effect |
|---|---|
| `text` | reply with an answer / clarifying question |
| `proposed_action` | propose the one wired self-service action (password-reset link) for confirmation |
| `escalation` | hand off to a human — **which creates a real Ticket + WorkflowInstance in ticket-workflow** (SPEC-ARO-041) |

Escalation routing is fully configured in this deployment
(`ESCALATION_DEFAULT_CATEGORY_ID` / `_SUPPORT_QUEUE_ID` / `_TEAM_NAME` all set), so an
`escalation` decision does produce a ticket.

What went wrong is purely in `_SYSTEM_PROMPT`:

1. RAG retrieved `accounts-new-hire-access-and-sso.md`, which describes the
   access-request-form process.
2. The prompt's `escalation` rule only named **hardware / physical inspection** and
   "anything clearly beyond what a text answer … can resolve". It said **nothing**
   about "the employee is explicitly asking you to open a ticket / get a human / have
   access granted".
3. So the model, seeing a relevant KB snippet, judged that a `text` answer (the
   process) *does* resolve it — and even narrated "I can't open the ticket for you
   from here", because nothing told it that escalating **is** how a ticket is opened.

## 3. The change (two iterations, each measured against the benchmark)

The code comment at this line names it: *"This is the point where an
evaluation-gated PROMPT_CHANGE would land."* The proper production path is the
evaluation-improvement candidate pipeline (draft → benchmark → gate → canary →
promote); here the **built-in default** was edited directly (a promoted candidate
override still stacks on top of it). Every iteration was scored with
`scripts/agent-accuracy-eval.sh` against the 22-case
`OpsMind IT Support Routing (extended)` dataset (`10000000-…-0003`).

### Iteration v1 — added "explicit request to act" to the escalation rule

Rule 1 gained "a relevant knowledge snippet does NOT mean the answer is text";
rule 3 gained "the employee explicitly asks you to open/file/raise a ticket … the
request needs someone to grant access to an application or system, provision or change
an account, or make a configuration change".

**Result:** the 4 explicit-ticket cases now escalate correctly — **but 3 genuine
cases regressed**, the model over-reading "needs someone to act":

| case | user request | should be | v1 gave |
|---|---|---|---|
| `printer-add-howto` | "how do I add the 3rd-floor printer to my Mac?" | `text` | `escalation` |
| `software-request-process` | "how do I request a Photoshop license?" | `text` | `escalation` |
| `account-locked-mfa-fails` | "I kept failing MFA and now I'm locked out, need back in" | `proposed_action` | `escalation` |

CLASSIFICATION_ACCURACY: **0.864** (19/22). The benchmark caught the regression — a
plausible prompt edit that a scored dataset flags before it ships.

### Iteration v2 — decide on the *verb*, not the topic (current)

Reframed around what the employee is asking the assistant **to do**, with an explicit
disambiguation block at the top:

- **QUESTION** about how something works / how to do it themselves ("how do I …",
  "what's the process for …", "where do I find …") → `text`, *even when it's about
  access, licenses, printers, or accounts*.
- **PASSWORD / lockout** ("I forgot my password", "I'm locked out", "MFA keeps
  failing and I can't get in") → `proposed_action`.
- **INSTRUCTION to the assistant to act** ("open a ticket for this", "request X
  access for me", "get IT to set up …", "have someone look at …"), or a
  physical/hardware problem → `escalation`.

**Result:** CLASSIFICATION_ACCURACY **0.955** (21/22), RESOLUTION_SUCCESS 0.955,
TOOL_SELECTION 1.0, POLICY_COMPLIANCE 1.0 — release gate `mvp-release-gate-v1`
**PASSED**. All 3 v1 regressions fixed; all 4 explicit-ticket cases still correct.

The one remaining miss: **`second-monitor-request`** — "I'd like a second monitor for
my home office — how do I get one?" (ground truth `ESCALATED_TO_HUMAN`, agent gave
`text`). This is genuinely borderline: it's phrased as a *question* ("how do I get
one") but is really an equipment request. See §6.

## 4. Prompt text — before / after

### Before (original)

```
You are an IT support chat assistant helping an employee inside a company's own
internal support tool. You must decide exactly one of 3 outcomes for this message turn:

1. "text" — answer directly, grounded in the knowledge snippets provided below if any
   are relevant, or ask one clarifying question if you need more information before you
   can help.
2. "proposed_action" — propose a concrete self-service action for the human to
   confirm. Right now exactly ONE real self-service action exists in this system:
   sending a password-reset link to the employee's own registered email, appropriate
   for password/account-lockout requests. Its risk_level is always LOW. Never propose
   any other action — no other self-service capability is wired up yet, and proposing
   one this system cannot actually perform would mislead the employee.
3. "escalation" — hand this off to a human support technician. Use this for anything
   requiring physical/hardware inspection (a broken or unresponsive device, physical
   damage, smoke/burning smell) or anything clearly beyond what a text answer or the
   one action above can resolve.

Never invent facts not grounded in the knowledge snippets given to you. If nothing
relevant was retrieved and the request isn't a password/hardware case, ask a
clarifying question rather than guessing.
```

### After (v2, current — `conversation_reasoning.py:79`)

```
You are an IT support chat assistant helping an employee inside a company's own
internal support tool. You must decide exactly one of 3 outcomes for this message turn.

The deciding signal is what the employee is asking you to do:
  - Asking a QUESTION about how something works or how to do it themselves
    ("how do I ...", "what's the process for ...", "where do I find ...") -> outcome 1,
    even when it is about access, licenses, printers, or accounts.
  - A PASSWORD or account-lockout problem ("I forgot my password", "I'm locked out",
    "MFA keeps failing and I can't get in") -> outcome 2.
  - Telling YOU to carry out a request or get a person to act ("open a ticket for
    this", "request X access for me", "get IT to set up ...", "have someone look
    at ..."), or a physical/hardware problem -> outcome 3.

1. "text" — answer directly, grounded in the knowledge snippets provided below if any
   are relevant, or ask one clarifying question if you need more information before you
   can help. This is the right outcome for every how-to / informational question,
   including how to request access or a license.
2. "proposed_action" — propose a concrete self-service action for the human to
   confirm. Right now exactly ONE real self-service action exists in this system:
   sending a password-reset link to the employee's own registered email, appropriate
   for password / account-lockout requests. Its risk_level is always LOW. Never
   propose any other action — no other self-service capability is wired up yet, and
   proposing one this system cannot actually perform would mislead the employee.
3. "escalation" — hand this off to a human support technician; this also opens a
   support ticket for them. Use it when ANY of these is true: the employee explicitly
   asks you to open / file / raise a ticket, or to get IT or a human to act; they ask
   you to actually request or set up access / an account / a system change FOR them
   (as an instruction to act now, not a question about the process); it needs physical
   or hardware inspection (a broken or unresponsive device, physical damage, smoke or
   a burning smell); or it is otherwise beyond what a text answer or the one action
   above can resolve. Do NOT reply with "text" describing the manual request process
   when the employee has told you to open the ticket for them.

Never invent facts not grounded in the knowledge snippets given to you. If nothing
relevant was retrieved and the request isn't a password / hardware /
explicit-request-to-act case, ask a clarifying question rather than guessing.
```

## 5. Evaluation-dataset change

Migration `c9f4a1b7e230` (SPEC-XEVAL-001) already added the 18-case
`OpsMind IT Support Routing (extended)` dataset. This change extends it to **22
cases** with a fourth sub-class — *explicit "do it for me" / access-grant requests*,
ground truth `ESCALATED_TO_HUMAN`:

| case_key | user_request_redacted |
|---|---|
| `open-ticket-app-access` | "since I need access to log into StarRez, help me open a ticket to have the IT team give me the access." (≈ the screenshot verbatim) |
| `request-shared-mailbox-access` | "please request access to the facilities shared mailbox for me — I need it for my new role." |
| `explicit-open-ticket-generic` | "can you open a ticket for this and have someone from IT get back to me?" |
| `provision-vpn-for-contractor` | "I need the IT team to set up VPN access for a contractor on my team — can you get that started?" |

So the benchmark now permanently measures whether the agent opens a ticket when told
to. Class balance: 10 escalation / 6 how-to / 6 password-self-service.

## 6. Residual + recommendation

- **`second-monitor-request`** stays wrong under v2. Two clean options:
  1. reword the case to match its intent — `"please request a second monitor for my
     home office"` (imperative → unambiguously `escalation`); or
  2. accept it as a genuine ~5% edge and let the LangSmith trend show whether it's
     stable noise. Either way the release gate (0.955 ≥ 0.90) is not at risk.
- **Roll this out properly for a real deployment**: instead of editing the default,
  create a `PROMPT_CHANGE` improvement candidate carrying the v2 text →
  `POST /evaluation/improvement-candidates` → `/benchmark` against `10000000-…-0003`
  → if it clears `mvp-release-gate-v1`, `/approve` → `/start-canary` → `/promote`. The
  promotion emits `improvement.promoted.v1`, the event-relay (SPEC-XREL-001) delivers
  it to agent-runtime's `/events/improvement-promoted`, and agent-runtime swaps the
  active prompt **live, with no redeploy** — and with an auditable eval record behind
  the change.

## 7. Results

| version | prompt | dataset | CLASSIFICATION_ACCURACY | RESOLUTION_SUCCESS | gate | notes |
|---|---|---|---|---|---|---|
| original | v0 | 6-case | 0.83 – 1.00 (varies) | same | PASSED | recurring miss `printer-not-printing-howto` (over-escalates) |
| original | v0 | 18-case | 1.00 | 1.00 | PASSED | one clean run |
| prompt-fix v1 | v1 | 22-case | **0.864** | 0.864 | PASSED | 4 explicit-ticket cases fixed; **3 how-to/self-service regressed** |
| prompt-fix **v2** | **v2** | 22-case | **0.955** (21/22) | 0.955 | PASSED | regressions fixed; only `second-monitor-request` wrong |

Every run pushed to LangSmith (`opsmind-eval-OpsMind IT Support Routing (extended)-v1-<runId>`)
with per-case `llm` runs, per-dimension feedback, and prompt/completion token counts.

## 8. Files

Modified:

```
services/agent-runtime-service/src/agentruntime/infrastructure/conversation_reasoning.py
    _SYSTEM_PROMPT — v0 -> v2 (see §4). No test change: test_conversation_reasoning.py
    asserts `system_msg["content"] == _SYSTEM_PROMPT` by reference, not literal text.
services/evaluation-improvement-service/migrations/versions/c9f4a1b7e230_seed_extended_routing_eval_dataset.py
    18 -> 22 cases (+ the 4 explicit-ticket escalation cases; case_count 22).
```

New:

```
docs/specs/cross-cutting/SPEC-XPROMPT-001-conversation-routing-prompt-change.md   (this file)
```

No other product code touched. The escalation → ticket-creation path, the RAG
retrieval, and the reasoning adapters are all unchanged — only the routing instruction
the model reads.
