"""SPEC-ARO-039 (phase-10 Conversational Intake): StaticConversationReasoningAdapter —
a deliberately simple placeholder for the real LLM/LangGraph-based reasoning this
conversation flow needs (see that adapter's own module docstring). These tests only
prove the 3-way discriminator wiring works, not that the decisions are "intelligent."
"""

from __future__ import annotations

import pytest

from agentruntime.application.records import KnowledgeSnippet
from agentruntime.infrastructure.conversation_reasoning import (
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
