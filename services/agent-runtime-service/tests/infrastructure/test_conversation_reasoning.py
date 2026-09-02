"""SPEC-ARO-039 (phase-10 Conversational Intake): StaticConversationReasoningAdapter —
a deliberately simple placeholder for the real LLM-based reasoning this conversation
flow needs (see that adapter's own module docstring). These tests only prove the 3-way
discriminator wiring works, not that the decisions are "intelligent."

AnthropicConversationReasoningAdapter's own tests below mirror
evaluation-improvement-service's own `tests/infrastructure/test_llm_judge.py` exactly —
same duck-typed fake client pattern, for the same reason (real, structural test
coverage without the real package installed or a network call made).
"""

from __future__ import annotations

import base64

import pytest

from agentruntime.application.records import AttachmentContent, KnowledgeSnippet
from agentruntime.infrastructure.conversation_reasoning import (
    AnthropicConversationReasoningAdapter,
    ConversationDecision,
    OpenAIConversationReasoningAdapter,
    StaticConversationReasoningAdapter,
)

pytestmark = pytest.mark.unit


def test_a_hardware_sounding_message_escalates() -> None:
    adapter = StaticConversationReasoningAdapter()

    outcome = adapter.decide("My laptop screen is broken and won't turn on", [])

    assert outcome.kind == "escalation"
    assert outcome.escalation_reason


def test_a_password_reset_message_proposes_a_low_risk_action() -> None:
    adapter = StaticConversationReasoningAdapter()

    outcome = adapter.decide("I need to reset my password", [])

    assert outcome.kind == "proposed_action"
    assert outcome.action_risk_level == "LOW"
    assert outcome.action_summary


def test_a_plain_question_with_knowledge_snippets_answers_with_text() -> None:
    adapter = StaticConversationReasoningAdapter()
    snippets = [KnowledgeSnippet(source_id="mem-1", snippet="Try restarting the VPN client.", score=0.9)]

    outcome = adapter.decide("My VPN keeps dropping", snippets)

    assert outcome.kind == "text"
    assert "Try restarting the VPN client." in outcome.text


def test_a_plain_question_with_no_knowledge_snippets_still_answers_with_text() -> None:
    adapter = StaticConversationReasoningAdapter()

    outcome = adapter.decide("Something is wrong with my computer", [])

    assert outcome.kind == "text"
    assert outcome.text


class _FakeParseResponse:
    def __init__(self, decision: ConversationDecision) -> None:
        self.parsed_output = decision


class _FakeMessages:
    def __init__(self, decision: ConversationDecision | None, raise_error: bool) -> None:
        self._decision = decision
        self._raise_error = raise_error
        self.last_call: dict | None = None

    def parse(self, **kwargs):  # noqa: ANN003, ANN201
        self.last_call = kwargs
        if self._raise_error:
            raise RuntimeError("anthropic api unreachable")
        return _FakeParseResponse(self._decision)


class _FakeAnthropicClient:
    def __init__(self, decision: ConversationDecision | None = None, raise_error: bool = False) -> None:
        self.messages = _FakeMessages(decision, raise_error)


def test_anthropic_adapter_maps_a_text_decision() -> None:
    client = _FakeAnthropicClient(decision=ConversationDecision(kind="text", text="Try restarting the VPN client."))
    adapter = AnthropicConversationReasoningAdapter(client, "claude-sonnet-5")

    outcome = adapter.decide("My VPN keeps dropping", [KnowledgeSnippet(source_id="mem-1", snippet="Restart the VPN client.", score=0.9)])

    assert outcome.kind == "text"
    assert outcome.text == "Try restarting the VPN client."
    # The retrieved snippet is threaded into the real prompt sent to the model.
    call = client.messages.last_call
    assert call is not None
    # content is a real multimodal content-block list (SPEC-ARO-039's own multimodal
    # follow-up), not a plain string — the prompt text lives in its first block.
    assert "Restart the VPN client." in call["messages"][0]["content"][0]["text"]


def test_anthropic_adapter_maps_a_proposed_action_decision() -> None:
    client = _FakeAnthropicClient(decision=ConversationDecision(
        kind="proposed_action", action_summary="Send a password-reset link to your registered email.", action_risk_level="LOW",
    ))
    adapter = AnthropicConversationReasoningAdapter(client, "claude-sonnet-5")

    outcome = adapter.decide("I need to reset my password", [])

    assert outcome.kind == "proposed_action"
    assert outcome.action_risk_level == "LOW"
    assert outcome.action_summary


def test_anthropic_adapter_maps_an_escalation_decision() -> None:
    client = _FakeAnthropicClient(decision=ConversationDecision(kind="escalation", escalation_reason="Hardware issue."))
    adapter = AnthropicConversationReasoningAdapter(client, "claude-sonnet-5")

    outcome = adapter.decide("My laptop screen is broken and won't turn on", [])

    assert outcome.kind == "escalation"
    assert outcome.escalation_reason == "Hardware issue."


def test_anthropic_adapter_fails_open_to_a_plain_text_response_on_error() -> None:
    client = _FakeAnthropicClient(raise_error=True)
    adapter = AnthropicConversationReasoningAdapter(client, "claude-sonnet-5")

    outcome = adapter.decide("Anything", [])

    assert outcome.kind == "text"
    assert outcome.text


# OpenAIConversationReasoningAdapter's own fakes mirror the real, nested
# response.choices[0].message.parsed shape (confirmed against the real installed
# `openai` package's own ParsedChatCompletion/ParsedChoice/ParsedChatCompletionMessage
# types) rather than reusing the Anthropic fakes above, which mirror a different real
# shape (response.parsed_output).
class _FakeOpenAIMessage:
    def __init__(self, decision: ConversationDecision) -> None:
        self.parsed = decision


class _FakeOpenAIChoice:
    def __init__(self, decision: ConversationDecision) -> None:
        self.message = _FakeOpenAIMessage(decision)


class _FakeOpenAIParseResponse:
    def __init__(self, decision: ConversationDecision) -> None:
        self.choices = [_FakeOpenAIChoice(decision)]


class _FakeChatCompletions:
    def __init__(self, decision: ConversationDecision | None, raise_error: bool) -> None:
        self._decision = decision
        self._raise_error = raise_error
        self.last_call: dict | None = None

    def parse(self, **kwargs):  # noqa: ANN003, ANN201
        self.last_call = kwargs
        if self._raise_error:
            raise RuntimeError("openai api unreachable")
        return _FakeOpenAIParseResponse(self._decision)


class _FakeOpenAIClient:
    def __init__(self, decision: ConversationDecision | None = None, raise_error: bool = False) -> None:
        self.chat = type("_Chat", (), {})()
        self.chat.completions = _FakeChatCompletions(decision, raise_error)


def test_openai_adapter_maps_a_text_decision() -> None:
    client = _FakeOpenAIClient(decision=ConversationDecision(kind="text", text="Try restarting the VPN client."))
    adapter = OpenAIConversationReasoningAdapter(client, "gpt-5-mini")

    outcome = adapter.decide("My VPN keeps dropping", [KnowledgeSnippet(source_id="mem-1", snippet="Restart the VPN client.", score=0.9)])

    assert outcome.kind == "text"
    assert outcome.text == "Try restarting the VPN client."
    # The retrieved snippet is threaded into the real prompt sent to the model.
    call = client.chat.completions.last_call
    assert call is not None
    # content is a real multimodal content-block list (SPEC-ARO-039's own multimodal
    # follow-up), not a plain string — the prompt text lives in its first block.
    assert "Restart the VPN client." in call["messages"][1]["content"][0]["text"]


def test_openai_adapter_maps_a_proposed_action_decision() -> None:
    client = _FakeOpenAIClient(decision=ConversationDecision(
        kind="proposed_action", action_summary="Send a password-reset link to your registered email.", action_risk_level="LOW",
    ))
    adapter = OpenAIConversationReasoningAdapter(client, "gpt-5-mini")

    outcome = adapter.decide("I need to reset my password", [])

    assert outcome.kind == "proposed_action"
    assert outcome.action_risk_level == "LOW"
    assert outcome.action_summary


def test_openai_adapter_maps_an_escalation_decision() -> None:
    client = _FakeOpenAIClient(decision=ConversationDecision(kind="escalation", escalation_reason="Hardware issue."))
    adapter = OpenAIConversationReasoningAdapter(client, "gpt-5-mini")

    outcome = adapter.decide("My laptop screen is broken and won't turn on", [])

    assert outcome.kind == "escalation"
    assert outcome.escalation_reason == "Hardware issue."


def test_openai_adapter_fails_open_to_a_plain_text_response_on_error() -> None:
    client = _FakeOpenAIClient(raise_error=True)
    adapter = OpenAIConversationReasoningAdapter(client, "gpt-5-mini")

    outcome = adapter.decide("Anything", [])

    assert outcome.kind == "text"
    assert outcome.text


# SPEC-ARO-039's own multimodal follow-up: both real adapters turn a real
# AttachmentContent's bytes into a real vision content block.
_FAKE_IMAGE_BYTES = b"\x89PNG-fake-bytes"


def test_anthropic_adapter_turns_an_image_attachment_into_a_real_vision_content_block() -> None:
    client = _FakeAnthropicClient(decision=ConversationDecision(kind="text", text="I can see a broken laptop screen."))
    adapter = AnthropicConversationReasoningAdapter(client, "claude-sonnet-5")

    outcome = adapter.decide(
        "What's wrong with this?", [],
        [AttachmentContent(attachment_ref="att-1", content=_FAKE_IMAGE_BYTES, mime_type="image/png")],
    )

    assert outcome.kind == "text"
    blocks = client.messages.last_call["messages"][0]["content"]
    assert blocks[0]["type"] == "text"
    assert blocks[1] == {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/png", "data": base64.b64encode(_FAKE_IMAGE_BYTES).decode("ascii")},
    }


def test_anthropic_adapter_never_forwards_a_non_image_attachment_as_visual_content() -> None:
    client = _FakeAnthropicClient(decision=ConversationDecision(kind="text", text="Here is what I found."))
    adapter = AnthropicConversationReasoningAdapter(client, "claude-sonnet-5")

    adapter.decide(
        "Please review this document", [],
        [AttachmentContent(attachment_ref="att-2", content=b"%PDF-fake", mime_type="application/pdf")],
    )

    blocks = client.messages.last_call["messages"][0]["content"]
    assert len(blocks) == 1
    assert blocks[0]["type"] == "text"


def test_openai_adapter_turns_an_image_attachment_into_a_real_vision_content_block() -> None:
    client = _FakeOpenAIClient(decision=ConversationDecision(kind="text", text="I can see a broken laptop screen."))
    adapter = OpenAIConversationReasoningAdapter(client, "gpt-5-mini")

    outcome = adapter.decide(
        "What's wrong with this?", [],
        [AttachmentContent(attachment_ref="att-1", content=_FAKE_IMAGE_BYTES, mime_type="image/png")],
    )

    assert outcome.kind == "text"
    blocks = client.chat.completions.last_call["messages"][1]["content"]
    assert blocks[0]["type"] == "text"
    assert blocks[1] == {
        "type": "image_url",
        "image_url": {"url": f"data:image/png;base64,{base64.b64encode(_FAKE_IMAGE_BYTES).decode('ascii')}"},
    }


def test_static_adapter_ignores_attachments_entirely() -> None:
    adapter = StaticConversationReasoningAdapter()

    outcome = adapter.decide(
        "I need to reset my password", [],
        [AttachmentContent(attachment_ref="att-1", content=_FAKE_IMAGE_BYTES, mime_type="image/png")],
    )

    assert outcome.kind == "proposed_action"
