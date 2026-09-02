"""SPEC-ARO-039 (phase-10 Conversational Intake): StaticConversationReasoningAdapter —
a deliberately simple, keyword-based placeholder for the real LLM-based reasoning this
conversation flow needs. Real LangGraph orchestration itself remains out of scope (the
frozen technology-baseline still lists "Agent Orchestration: LangGraph behind internal
abstractions" as "Provisional," and this single-turn `decide()` port has no
multi-step-plan concept for LangGraph to orchestrate) — but genuine LLM calls behind
this exact port are now real, from either of 2 providers: AnthropicConversationReasoningAdapter
and OpenAIConversationReasoningAdapter below, both built against the identical
`ConversationDecision` schema so `Settings.conversation_reasoning_mode` can pick either
one without SendMessageService (this port's one real caller) ever knowing which.

StaticConversationReasoningAdapter mirrors LoggingToolGatewayPort/NoOpTicketSnapshotPort/
StaticCapabilityPolicyAdapter's own established precedent in this exact codebase: a
real, working, honestly-labeled placeholder adapter behind a real port
(ConversationReasoningPort) — the default every hermetic test in this service still
relies on, and the safe fallback `container._build_conversation_reasoning_port()` uses
if the real adapter can't be constructed.

Multimodal follow-up: both real adapters now turn `attachments` (real bytes
SendMessageService already fetched via AttachmentClientPort) into real vision content
blocks — see `_image_attachments()`'s own docstring for the honest limit on that scope.
"""

from __future__ import annotations

import base64
import logging
from typing import Literal

from pydantic import BaseModel, Field

from agentruntime.application.records import AttachmentContent, KnowledgeSnippet, ReasoningOutcome

logger = logging.getLogger("agentruntime.infrastructure.conversation_reasoning")

# Deliberately small, illustrative keyword sets — not a real intent classifier.
_ESCALATION_KEYWORDS = ("broken screen", "won't turn on", "hardware", "physical damage", "smoke", "burning smell")
_SELF_SERVICE_KEYWORDS = ("reset my password", "password reset", "unlock my account", "forgot my password")


class StaticConversationReasoningAdapter:
    def decide(
        self, message_text: str, knowledge_snippets: list[KnowledgeSnippet], attachments: list[AttachmentContent] | None = None,
    ) -> ReasoningOutcome:
        # Deliberately ignores attachments — this placeholder never looks at anything
        # beyond its own keyword match against message_text; only the 2 real LLM
        # adapters below actually see attachment bytes.
        lowered = message_text.lower()

        if any(keyword in lowered for keyword in _ESCALATION_KEYWORDS):
            return ReasoningOutcome(
                kind="escalation",
                escalation_reason="This looks like a hardware issue that needs a human technician to inspect in person.",
            )

        if any(keyword in lowered for keyword in _SELF_SERVICE_KEYWORDS):
            return ReasoningOutcome(
                kind="proposed_action", action_summary="Send a password-reset link to your registered email.",
                action_risk_level="LOW",
            )

        if knowledge_snippets:
            top = max(knowledge_snippets, key=lambda snippet: snippet.score)
            return ReasoningOutcome(kind="text", text=f"Here's what I found that might help: {top.snippet}")

        return ReasoningOutcome(
            kind="text",
            text="Thanks for the details — could you tell me a bit more about what you're seeing?",
        )


_SYSTEM_PROMPT = (
    "You are an IT support chat assistant helping an employee inside a company's own "
    "internal support tool. You must decide exactly one of 3 outcomes for this message "
    "turn:\n\n"
    "1. \"text\" — answer directly, grounded in the knowledge snippets provided below if "
    "any are relevant, or ask one clarifying question if you need more information "
    "before you can help.\n"
    "2. \"proposed_action\" — propose a concrete self-service action for the human to "
    "confirm. Right now exactly ONE real self-service action exists in this system: "
    "sending a password-reset link to the employee's own registered email, appropriate "
    "for password/account-lockout requests. Its risk_level is always LOW. Never propose "
    "any other action — no other self-service capability is wired up yet, and proposing "
    "one this system cannot actually perform would mislead the employee.\n"
    "3. \"escalation\" — hand this off to a human support technician. Use this for "
    "anything requiring physical/hardware inspection (a broken or unresponsive device, "
    "physical damage, smoke/burning smell) or anything clearly beyond what a text "
    "answer or the one action above can resolve.\n\n"
    "Never invent facts not grounded in the knowledge snippets given to you. If nothing "
    "relevant was retrieved and the request isn't a password/hardware case, ask a "
    "clarifying question rather than guessing."
)


class ConversationDecision(BaseModel):
    """The structured-output schema `AnthropicConversationReasoningAdapter` extracts —
    all fields optional (mirrors `ReasoningOutcome` itself) since only a subset applies
    per `kind`; `_to_outcome()` below is where that's enforced, not this schema.
    """

    kind: Literal["text", "proposed_action", "escalation"]
    text: str | None = Field(default=None, description="The reply text, when kind is 'text'.")
    action_summary: str | None = Field(default=None, description="A plain-language summary of the proposed action, when kind is 'proposed_action'.")
    action_risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"] | None = Field(
        default=None, description="Always LOW for the one real action this system supports today, when kind is 'proposed_action'.",
    )
    escalation_reason: str | None = Field(default=None, description="A short, human-readable reason, when kind is 'escalation'.")


class AnthropicConversationReasoningAdapter:
    """SPEC-ARO-039 follow-up: the real judge, backed by the `anthropic` SDK's
    structured-output `messages.parse()` — the identical mechanism/SDK
    evaluation-improvement-service's own `AnthropicQualityJudge` already established in
    this codebase (`infrastructure.graders.llm_judge`), not a new one-off integration
    pattern. `client` stays untyped (`object`) for the same reason that one does: this
    class is unit-testable against a duck-typed fake without the real package
    installed or a network call made.

    Unlike that judge (an offline, batch grading call), this runs synchronously inside
    `SendMessageService`'s own inline HTTP request path (see that service's own module
    docstring) — latency matters here in a way it doesn't for offline grading, which is
    why this defaults to a faster model than the judge's own default (see
    `Settings.conversation_reasoning_model`'s own comment).
    """

    def __init__(self, client: object, model: str) -> None:
        self._client = client
        self._model = model

    def decide(
        self, message_text: str, knowledge_snippets: list[KnowledgeSnippet], attachments: list[AttachmentContent] | None = None,
    ) -> ReasoningOutcome:
        try:
            content: list[dict] = [{"type": "text", "text": _build_prompt(message_text, knowledge_snippets)}]
            for image in _image_attachments(attachments):
                content.append({
                    "type": "image",
                    "source": {"type": "base64", "media_type": image.mime_type, "data": base64.b64encode(image.content).decode("ascii")},
                })
            response = self._client.messages.parse(
                model=self._model, max_tokens=1024, system=_SYSTEM_PROMPT,
                messages=[{"role": "user", "content": content}],
                output_format=ConversationDecision,
            )
            decision: ConversationDecision = response.parsed_output
            return _to_outcome(decision)
        except Exception:
            # domain-rules: "a retrieval failure degrades to a plainer answer or an
            # escalation, never a hallucinated citation" — the same fail-open posture
            # this codebase already applies to a failed LLM Judge call
            # (infrastructure.graders.llm_judge's own module docstring), applied here
            # to a failed reasoning call: never silently fabricate a decision, and
            # never let an LLM outage take down the whole message turn.
            logger.warning("conversation reasoning call to Anthropic failed; falling back to a plain text response", exc_info=True)
            return ReasoningOutcome(
                kind="text",
                text="I'm having trouble processing that right now — could you try rephrasing, or let me know if this needs a human technician?",
            )


class OpenAIConversationReasoningAdapter:
    """A second real ConversationReasoningPort implementation, this service's own
    first use of the `openai` SDK — added alongside AnthropicConversationReasoningAdapter
    rather than replacing it, so `Settings.conversation_reasoning_mode` can pick either
    real provider. Uses the SDK's own structured-output mechanism,
    `client.chat.completions.parse(response_format=...)` (confirmed against the real
    installed `openai` package: `ParsedChatCompletionMessage` carries the parsed object
    at `.parsed`) — the OpenAI-shaped equivalent of AnthropicConversationReasoningAdapter's
    own `messages.parse(output_format=...)` call, reusing the exact same
    `ConversationDecision` schema and `_to_outcome()`/`_build_prompt()` helpers since the
    two providers only differ in call shape, not in what's being asked for. Same
    untyped `client: object` DI seam and the same fail-open-to-plain-text posture as
    that adapter, for the same reasons.
    """

    def __init__(self, client: object, model: str) -> None:
        self._client = client
        self._model = model

    def decide(
        self, message_text: str, knowledge_snippets: list[KnowledgeSnippet], attachments: list[AttachmentContent] | None = None,
    ) -> ReasoningOutcome:
        try:
            user_content: list[dict] = [{"type": "text", "text": _build_prompt(message_text, knowledge_snippets)}]
            for image in _image_attachments(attachments):
                data_url = f"data:{image.mime_type};base64,{base64.b64encode(image.content).decode('ascii')}"
                user_content.append({"type": "image_url", "image_url": {"url": data_url}})
            response = self._client.chat.completions.parse(
                model=self._model,
                messages=[
                    {"role": "system", "content": _SYSTEM_PROMPT},
                    {"role": "user", "content": user_content},
                ],
                response_format=ConversationDecision,
            )
            decision: ConversationDecision = response.choices[0].message.parsed
            return _to_outcome(decision)
        except Exception:
            # Same fail-open reasoning as AnthropicConversationReasoningAdapter's own
            # except clause — never fabricate a decision, never let an LLM outage take
            # down the whole message turn.
            logger.warning("conversation reasoning call to OpenAI failed; falling back to a plain text response", exc_info=True)
            return ReasoningOutcome(
                kind="text",
                text="I'm having trouble processing that right now — could you try rephrasing, or let me know if this needs a human technician?",
            )


def _image_attachments(attachments: list[AttachmentContent] | None) -> list[AttachmentContent]:
    """Both real adapters only turn image/* attachments into real vision content
    blocks. A non-image attachment (application/pdf is the one other type
    attachment-service's own AttachmentProperties.allowed-mime-types accepts today) is
    still fetched by SendMessageService but never reaches the model as content — the
    OpenAI chat.completions.parse call this adapter uses has no inline-base64-PDF
    content-block type (only Anthropic's Messages API has a native "document" block for
    that), and giving one provider a capability the other genuinely lacks would make
    `Settings.conversation_reasoning_mode` a silent behavior change, not just a provider
    swap. A deliberately narrow, self-flagged scope rather than a fabricated one.
    """

    if not attachments:
        return []
    return [attachment for attachment in attachments if attachment.mime_type.startswith("image/")]


def _to_outcome(decision: ConversationDecision) -> ReasoningOutcome:
    if decision.kind == "proposed_action":
        return ReasoningOutcome(
            kind="proposed_action",
            action_summary=decision.action_summary or "Send a password-reset link to your registered email.",
            action_risk_level=decision.action_risk_level or "LOW",
        )
    if decision.kind == "escalation":
        return ReasoningOutcome(
            kind="escalation",
            escalation_reason=decision.escalation_reason or "The assistant determined this issue needs human assistance.",
        )
    return ReasoningOutcome(kind="text", text=decision.text or "Could you tell me a bit more about what you're seeing?")


def _build_prompt(message_text: str, knowledge_snippets: list[KnowledgeSnippet]) -> str:
    if knowledge_snippets:
        snippets_block = "\n".join(f"- (score {s.score:.2f}, source {s.source_id}): {s.snippet}" for s in knowledge_snippets)
    else:
        snippets_block = "(none retrieved for this message)"
    return f"Employee's message:\n{message_text}\n\nKnowledge snippets retrieved for this message:\n{snippets_block}\n"
