"""SPEC-ARO-039 follow-up: `_build_conversation_reasoning_port()`'s own real/fake
mode-toggle, mirroring evaluation-improvement-service's own `_build_quality_judge()`
precedent for the same real-vs-placeholder seam over the same `anthropic` SDK — and,
for "openai", this service's own first use of that SDK.
"""

from __future__ import annotations

import pytest

from agentruntime.container import _build_conversation_reasoning_port
from agentruntime.infrastructure.conversation_reasoning import (
    AnthropicConversationReasoningAdapter,
    OpenAIConversationReasoningAdapter,
    StaticConversationReasoningAdapter,
)
from agentruntime.settings import Settings

pytestmark = pytest.mark.unit


def test_default_mode_returns_the_static_placeholder() -> None:
    port = _build_conversation_reasoning_port(Settings(conversation_reasoning_mode="static"))
    assert isinstance(port, StaticConversationReasoningAdapter)


def test_anthropic_mode_with_a_key_constructs_the_real_adapter() -> None:
    # anthropic.Anthropic(...) construction itself never makes a network call — no
    # real key or reachable network is needed for this to succeed.
    port = _build_conversation_reasoning_port(
        Settings(conversation_reasoning_mode="anthropic", anthropic_api_key="fake-key-for-construction-only"),
    )
    assert isinstance(port, AnthropicConversationReasoningAdapter)


def test_openai_mode_with_a_key_constructs_the_real_adapter() -> None:
    # openai.OpenAI(...) construction itself never makes a network call either.
    port = _build_conversation_reasoning_port(
        Settings(conversation_reasoning_mode="openai", openai_api_key="fake-key-for-construction-only"),
    )
    assert isinstance(port, OpenAIConversationReasoningAdapter)
