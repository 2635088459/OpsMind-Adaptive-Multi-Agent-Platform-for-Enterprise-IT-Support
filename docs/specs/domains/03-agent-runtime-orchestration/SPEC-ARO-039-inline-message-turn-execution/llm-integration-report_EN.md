# Real LLM Integration for `ConversationReasoningPort` — Design Report

> Companion to this spec's own `traceability-entry.yaml` (2026-09-02 follow-up entries). Written because a direct verbal summary left the scope ambiguous — this is the detailed, technical account of exactly what was built, why, and what was deliberately not attempted.

## 1. The one-line correction first

**No LangGraph was built.** Nothing in this change touches multi-step agent orchestration. What was built is two genuine LLM API integrations (Anthropic, OpenAI) plugged behind an **existing, already-defined** interface (`ConversationReasoningPort`) that this codebase already had — replacing (well: supplementing) a keyword-matching placeholder with a real model call for the exact same, narrow, single-turn decision.

The frozen technology baseline (`docs/low-level-design/shared/technology-baseline`) lists "Agent Orchestration: LangGraph behind internal abstractions" as **Provisional** — i.e. planned, not built. That status is unchanged by this work. The reason is structural, not just "we didn't get to it": `ConversationReasoningPort.decide()` takes exactly `(message_text: str, knowledge_snippets: list[KnowledgeSnippet])` and returns one `ReasoningOutcome`. There is no conversation-history parameter, no multi-step plan, no tool-call loop, no graph of nodes/edges — nothing for LangGraph to actually orchestrate. Building LangGraph for real would mean redesigning this port's signature and its one caller (`SendMessageService`), which is a materially larger, separate piece of work than "make the existing decision smarter."

## 2. What actually exists now — the shape of it

```
ConversationReasoningPort (application/ports_out.py)
        │
        │  .decide(message_text, knowledge_snippets) -> ReasoningOutcome
        │
   ┌────┴────────────────┬──────────────────────┐
   │                     │                      │
StaticConversationReasoningAdapter   AnthropicConversationReasoningAdapter   OpenAIConversationReasoningAdapter
(keyword match, no network)         (real anthropic SDK call)               (real openai SDK call)
```

All three live in `infrastructure/conversation_reasoning.py`. `SendMessageService` — the one real caller, which executes synchronously inside the `POST /api/v1/conversations/{id}/messages` HTTP request — never knows which one is wired in; it just calls `.decide(...)` and branches on `outcome.kind`.

### 2.1 Which one runs is a settings toggle, not a code change

```python
conversation_reasoning_mode: Literal["static", "anthropic", "openai"] = "static"
```

`"static"` is the default everywhere — every hermetic test in the service, and the actually-running Docker container right now, use it. Switching providers is an environment variable, not a deploy of different code:

| Env var | Effect |
|---|---|
| `CONVERSATION_REASONING_MODE=static` (default) | Keyword placeholder, zero network calls, zero cost |
| `CONVERSATION_REASONING_MODE=anthropic` + `ANTHROPIC_API_KEY=...` | Real Claude call |
| `CONVERSATION_REASONING_MODE=openai` + `OPENAI_API_KEY=...` | Real GPT call |

If the mode is set to a real provider but the client can't be constructed (missing key, package import failure, anything), `container.py`'s `_build_conversation_reasoning_port()` catches the exception, logs a warning, and **falls back to the static placeholder** rather than crashing the service at startup. This mirrors a pattern this exact codebase's sibling service (`evaluation-improvement-service`) already established for its own LLM-judge feature (`llm_judge_mode`) — not a new idea invented here.

### 2.2 The one thing both real providers actually decide

Both adapters extract a **structured, typed** response from the model — not free text that then gets parsed with regex — using each SDK's own native structured-output mechanism:

- Anthropic: `client.messages.parse(..., output_format=ConversationDecision)` → the parsed object is at `response.parsed_output`.
- OpenAI: `client.chat.completions.parse(..., response_format=ConversationDecision)` → the parsed object is at `response.choices[0].message.parsed`.

These are **different real shapes** (confirmed by inspecting the actually-installed `openai` package's own types, not assumed) — which is why each adapter has its own dedicated unit-test fakes rather than sharing one.

Both parse into the exact same schema:

```python
class ConversationDecision(BaseModel):
    kind: Literal["text", "proposed_action", "escalation"]
    text: str | None = None
    action_summary: str | None = None
    action_risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"] | None = None
    escalation_reason: str | None = None
```

This is the same discriminated-union shape `ReasoningOutcome` (the pre-existing return type) already used — the model is being asked to fill in exactly the same 3-way decision the static placeholder made by keyword-matching, just with real judgment instead.

### 2.3 The system prompt is deliberately narrow, not "be a helpful assistant"

The prompt tells the model there are exactly 3 legal outcomes and, critically, **names the one and only real self-service action that exists anywhere downstream today**: sending a password-reset email, always `LOW` risk. It explicitly instructs the model never to propose anything else.

This constraint is not a stylistic choice — it's an honesty requirement forced by the rest of the codebase's own current state. `agent-runtime-service`'s own tool-dispatch adapter (`ToolGatewayPort`) is *itself* still a placeholder (`LoggingToolGatewayPort`) that only logs a fake "DISPATCHED" acknowledgement; it never calls tool-integration-gateway's real API. If the LLM were allowed to propose an arbitrary action ("I'll restart your VPN service for you"), the system would accept the user's confirmation and then... do nothing real. Scoping the prompt to the one action that is at least *structurally* wired end-to-end (even if the ultimate execution is still fake) keeps the assistant's own claims truthful about what it can do, matching the same "never fabricate a capability" discipline this codebase applies everywhere else (e.g. `RawOutputForbiddenException`'s own reasoning, `TicketWorkflowClientPort`'s own reasoning about whose identity a call carries).

### 2.4 Failure handling: fail open, never fabricate

If the API call itself throws (network error, auth failure, rate limit, malformed response) — the `except Exception` branch in `decide()` catches it, logs a warning, and returns a plain, honest `ReasoningOutcome(kind="text", text="I'm having trouble processing that right now...")`. It never returns a guessed decision, and it never lets an LLM outage take down the whole HTTP request the employee is waiting on.

### 2.5 Cost/latency-aware model defaults

`send_message()` runs **synchronously, inline, inside the employee's own HTTP request** (confirmed by reading `SendMessageService`'s own module docstring: "bypasses the existing async claim/complete worker queue"). This is unlike `evaluation-improvement-service`'s own LLM-judge use case, which grades test cases offline in batch and can tolerate a slower, more expensive model. That's why the model defaults chosen here (`claude-sonnet-5` for Anthropic, `gpt-5-mini` for OpenAI) are the faster/cheaper tier in each provider's own lineup, not the flagship reasoning-heavy tier — a real employee is watching a "..." typing indicator while this call is in flight.

## 3. Your multimodal question, answered honestly

**Yes — OpenAI has real multimodal models, and `gpt-5-mini` (the current default) is one of them.** The "mini" tier in OpenAI's current model families keeps native image (and increasingly audio) input while pricing meaningfully below the flagship tier — it is the correct "cost-effective but still multimodal" choice, not a stripped-down text-only variant. If you want an even cheaper floor and are willing to trade off some quality, OpenAI also ships a "nano"-class tier below "mini" in the same family; I did not default to it because the fallback-to-static safety net means a wrong/low-quality real decision is worse than the placeholder, and "mini" is the more defensible default without live-testing both.

**But here is the honest, important caveat: none of that multimodal capability is actually reachable today**, for a structural reason that has nothing to do with which model string is configured:

- `ConversationDecision`'s prompt is built purely from `message_text` and `knowledge_snippets` (`_build_prompt()` in `conversation_reasoning.py`). There is no image content anywhere in that call.
- `SendMessageCommand` **does** carry `attachment_refs: tuple[str, ...]` — so the API contract already has a hook for attachments — but tracing every use of that field shows it is only ever (a) recorded into the `PRE_KNOWLEDGE_RETRIEVAL` checkpoint's JSON payload, and (b) otherwise ignored. It is never passed to `KnowledgeRetrievalPort.search()` or to `ConversationReasoningPort.decide()`.
- Going one level deeper: `attachment_refs`' own docstring in `commands.py` says outright that these are "opaque, already-uploaded `ready`-state references per **the shared attachments capability's own contract, chartered but not yet built**." There is no real attachment-upload/storage service anywhere in this entire platform yet (confirmed earlier this session, across all 8 backend domains) — so even if I wired the reasoning adapter to accept an attachment reference and fetch its bytes, there is currently nothing real to fetch from.

So: **the model choice is multimodal-capable and cost-effective; the surrounding system is not yet multimodal end-to-end.** Turning this into a real feature (an employee attaches a screenshot of an error, the assistant actually looks at it) would need, in order: (1) the shared attachments capability actually built (upload + storage + a real read-back API — currently only chartered, per project memory), (2) `SendMessageService` passing the real attachment content (not just the opaque ref) through to `decide()`, and (3) each adapter's `_build_prompt()` extended to include image content blocks in the real request payload (both SDKs support this; neither adapter does it today). That is a real, separate, multi-part piece of work — not attempted here, and not silently half-done — flagged for you to decide whether it's worth prioritizing.

## 4. What was verified, and what was not

**Verified, for real:**
- 548 unit tests pass (up from 537 before this work), including new tests for both adapters' text/proposed_action/escalation mapping and their fail-open behavior, each against a duck-typed fake client shaped exactly like the real SDK's own response objects (confirmed against the actually-installed packages, not assumed).
- A container-level test confirms both `"anthropic"` and `"openai"` modes construct the right adapter class.
- Architecture tests (5/5) and import-linter layering contracts (3/3: interfaces→application→domain, domain must not depend on web/ORM frameworks, application must not depend on infrastructure) all still pass — this addition didn't cross any layering boundary it shouldn't have.
- The real Docker image was rebuilt twice (once per provider added) with the new `anthropic`/`openai` runtime dependencies, and the container was confirmed healthy and unchanged in behavior (still defaults to the static placeholder).

**Not verified, honestly:** this environment has no real `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` configured, so no actual network call to either provider's real API has been made. Everything above the "does this compile and route correctly" level — i.e. whether the *real* model's judgment is actually good for this use case — is unverified until you supply real credentials and try it.

## 5. Files touched

| File | What changed |
|---|---|
| `infrastructure/conversation_reasoning.py` | Added `ConversationDecision` schema, `AnthropicConversationReasoningAdapter`, `OpenAIConversationReasoningAdapter`, shared prompt/mapping helpers |
| `application/ports_out.py` | `ConversationReasoningPort`'s own docstring updated to describe all 3 real adapters |
| `settings.py` | `conversation_reasoning_mode` (`static`/`anthropic`/`openai`), `anthropic_api_key`, `openai_api_key`, `conversation_reasoning_anthropic_model`, `conversation_reasoning_openai_model` |
| `container.py` | `_build_conversation_reasoning_port()` — the mode-toggle/degrade-to-safe-default wiring |
| `pyproject.toml` / `uv.lock` | Added `anthropic>=0.69`, `openai>=3.0` as real runtime dependencies |
| `tests/infrastructure/test_conversation_reasoning.py` | 8 new tests (4 per provider) against duck-typed fakes |
| `tests/test_container.py` | New file — 3 tests for the mode-toggle wiring |
| `infrastructure/docker-compose/full-platform.yml` | `CONVERSATION_REASONING_MODE`/`ANTHROPIC_API_KEY`/`OPENAI_API_KEY` env vars, all empty/safe by default |
| This spec's own `traceability-entry.yaml` | Two follow-up entries recording exactly this |
