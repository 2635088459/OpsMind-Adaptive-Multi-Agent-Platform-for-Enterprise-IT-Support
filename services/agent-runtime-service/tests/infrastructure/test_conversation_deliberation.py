"""ConversationDeliberationPort adapters — SingleTurn (behaviour-preserving) and
the real LangGraph loop. A fake ConversationReasoningPort scripts the per-turn
decisions so the graph's control flow (branch, loop, cap) is what's under test.
"""

from __future__ import annotations

import pytest

from agentruntime.application.records import KnowledgeSnippet, ReasoningOutcome
from agentruntime.infrastructure.conversation_deliberation import (
    LangGraphConversationDeliberationAdapter,
    SingleTurnDeliberationAdapter,
)

pytestmark = pytest.mark.unit


def _text(text: str) -> ReasoningOutcome:
    return ReasoningOutcome(kind="text", text=text)


def _snip(source_id: str) -> KnowledgeSnippet:
    return KnowledgeSnippet(source_id=source_id, snippet=f"content of {source_id}", score=0.9)


class FakeReasoning:
    def __init__(self, outcomes: list[ReasoningOutcome]) -> None:
        self._outcomes = list(outcomes)
        self.calls: list[tuple[str, int]] = []

    def decide(self, message_text, knowledge_snippets, attachments=None):
        self.calls.append((message_text, len(knowledge_snippets)))
        return self._outcomes[min(len(self.calls) - 1, len(self._outcomes) - 1)]


class FakeRetrieve:
    def __init__(self, results: list[list[KnowledgeSnippet]] | None = None, *, raises: bool = False) -> None:
        self._results = results or []
        self._raises = raises
        self.queries: list[str] = []

    def __call__(self, query: str) -> list[KnowledgeSnippet]:
        self.queries.append(query)
        if self._raises:
            raise RuntimeError("knowledge service down")
        idx = len(self.queries) - 1
        return self._results[idx] if idx < len(self._results) else []


# --- SingleTurn ---------------------------------------------------------------

def test_single_turn_calls_decide_once_and_never_retrieves() -> None:
    reasoning = FakeReasoning([_text("here you go")])
    retrieve = FakeRetrieve()

    result = SingleTurnDeliberationAdapter(reasoning).deliberate("help", [_snip("kb-1")], None, retrieve)

    assert result.outcome.text == "here you go"
    assert result.iterations == 1
    assert reasoning.calls == [("help", 1)]
    assert retrieve.queries == []


# --- LangGraph loop ---------------------------------------------------------

def test_langgraph_stops_immediately_when_the_first_answer_is_well_supported() -> None:
    reasoning = FakeReasoning([_text("restart the VPN client")])
    retrieve = FakeRetrieve()

    result = LangGraphConversationDeliberationAdapter(reasoning, max_iterations=3).deliberate(
        "vpn broken", [_snip("kb-vpn")], None, retrieve
    )

    assert result.iterations == 1
    assert retrieve.queries == []
    assert result.outcome.text == "restart the VPN client"


def test_langgraph_retrieves_again_when_the_first_answer_has_no_supporting_snippets() -> None:
    reasoning = FakeReasoning([_text("try turning it off and on"), _text("here is the documented fix")])
    retrieve = FakeRetrieve([[_snip("kb-found")]])

    result = LangGraphConversationDeliberationAdapter(reasoning, max_iterations=3).deliberate(
        "printer offline", [], None, retrieve
    )

    assert result.iterations == 2
    assert len(retrieve.queries) == 1
    assert "printer offline" in retrieve.queries[0]
    assert [s.source_id for s in result.snippets_used] == ["kb-found"]
    assert result.outcome.text == "here is the documented fix"


def test_langgraph_retrieves_again_on_hedging_language_even_with_snippets() -> None:
    reasoning = FakeReasoning([_text("I don't have enough information to be sure"), _text("confirmed answer")])
    retrieve = FakeRetrieve([[_snip("kb-2")]])

    result = LangGraphConversationDeliberationAdapter(reasoning, max_iterations=3).deliberate(
        "mfa reset", [_snip("kb-1")], None, retrieve
    )

    assert result.iterations == 2
    assert result.outcome.text == "confirmed answer"


def test_langgraph_is_bounded_by_max_iterations() -> None:
    reasoning = FakeReasoning([_text("still not sure, unable to confirm")])  # always hedges
    retrieve = FakeRetrieve()

    result = LangGraphConversationDeliberationAdapter(reasoning, max_iterations=3).deliberate(
        "obscure issue", [], None, retrieve
    )

    assert result.iterations == 3
    assert len(retrieve.queries) == 2  # refine runs between the 3 reason passes
    assert reasoning.calls and all(c[0] == "obscure issue" for c in reasoning.calls)


def test_langgraph_does_not_loop_on_a_proposed_action() -> None:
    reasoning = FakeReasoning([ReasoningOutcome(kind="proposed_action", action_summary="reset password", action_risk_level="LOW")])
    retrieve = FakeRetrieve()

    result = LangGraphConversationDeliberationAdapter(reasoning, max_iterations=3).deliberate(
        "reset my password", [], None, retrieve
    )

    assert result.iterations == 1
    assert result.outcome.kind == "proposed_action"
    assert retrieve.queries == []


def test_langgraph_stops_gracefully_if_a_refine_retrieval_fails() -> None:
    reasoning = FakeReasoning([_text("not enough information")])
    retrieve = FakeRetrieve(raises=True)

    result = LangGraphConversationDeliberationAdapter(reasoning, max_iterations=3).deliberate(
        "thing", [], None, retrieve
    )

    # refine caught the error, loop continued to the iteration cap without snippets
    assert result.iterations == 3
    assert result.snippets_used == []
