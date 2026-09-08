"""ConversationDeliberationPort adapters.

``SingleTurnDeliberationAdapter`` is the default and is behaviourally identical
to the pre-existing "retrieve once, then decide()" path — it exists only so
SendMessageService always talks to one port.

``LangGraphConversationDeliberationAdapter`` is the real multi-step loop the
frozen technology-baseline listed as "Provisional": a genuine
``langgraph.StateGraph`` with a ``reason`` node, a ``refine`` node, and a
conditional edge between them. When the first answer is plain text that looks
under-supported (no snippets, or hedging language), the graph re-queries
knowledge with a refined query and reasons again, up to ``max_iterations``.

Honest scope, same convention as ``StaticConversationReasoningAdapter``: the
per-node *decisions* still come from ``ConversationReasoningPort`` (static or a
real LLM); the "is this answer well-supported?" check and the query-refinement
are deterministic heuristics, not a trained planner. LangGraph provides the
control flow — the loop, the branch, the state threading — which is the piece
that genuinely did not exist before.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any, TypedDict

from agentruntime.application.ports_out import ConversationReasoningPort
from agentruntime.application.records import (
    AttachmentContent,
    DeliberationResult,
    KnowledgeSnippet,
    ReasoningOutcome,
)

logger = logging.getLogger("agentruntime.infrastructure.conversation_deliberation")

_Retrieve = Callable[[str], list[KnowledgeSnippet]]

# Hedging phrases that mean "I answered, but I wasn't sure" — worth one more
# retrieval pass. Deliberately small and illustrative, like
# StaticConversationReasoningAdapter's own keyword sets.
_LOW_CONFIDENCE_MARKERS = (
    "i don't have", "i do not have", "not enough information", "couldn't find", "could not find",
    "i'm not sure", "i am not sure", "unable to", "no relevant", "cannot determine", "don't know",
)


class SingleTurnDeliberationAdapter:
    def __init__(self, reasoning_port: ConversationReasoningPort) -> None:
        self._reasoning_port = reasoning_port

    def deliberate(
        self,
        message_text: str,
        initial_snippets: list[KnowledgeSnippet],
        attachments: list[AttachmentContent] | None,
        retrieve: _Retrieve,
    ) -> DeliberationResult:
        outcome = self._reasoning_port.decide(message_text, initial_snippets, attachments)
        return DeliberationResult(outcome=outcome, snippets_used=list(initial_snippets), iterations=1)


class _State(TypedDict):
    message_text: str
    attachments: list[AttachmentContent] | None
    snippets: list[KnowledgeSnippet]
    iterations: int
    outcome: ReasoningOutcome | None


def _looks_under_supported(outcome: ReasoningOutcome, snippets: list[KnowledgeSnippet]) -> bool:
    if outcome.kind != "text":
        return False
    if not snippets:
        return True
    text = (outcome.text or "").lower()
    return any(marker in text for marker in _LOW_CONFIDENCE_MARKERS)


def _refined_query(message_text: str, iteration: int) -> str:
    hints = ("troubleshooting steps fix", "error resolution known issue workaround")
    return f"{message_text} {hints[min(iteration, len(hints) - 1)]}"


def _merge_snippets(existing: list[KnowledgeSnippet], more: list[KnowledgeSnippet]) -> list[KnowledgeSnippet]:
    by_id = {s.source_id: s for s in existing}
    for snippet in more:
        by_id.setdefault(snippet.source_id, snippet)
    return list(by_id.values())


class LangGraphConversationDeliberationAdapter:
    def __init__(self, reasoning_port: ConversationReasoningPort, max_iterations: int = 3) -> None:
        self._reasoning_port = reasoning_port
        self._max_iterations = max(1, max_iterations)

    def deliberate(
        self,
        message_text: str,
        initial_snippets: list[KnowledgeSnippet],
        attachments: list[AttachmentContent] | None,
        retrieve: _Retrieve,
    ) -> DeliberationResult:
        from langgraph.graph import END, StateGraph

        def reason(state: _State) -> dict[str, Any]:
            outcome = self._reasoning_port.decide(state["message_text"], state["snippets"], state["attachments"])
            return {"outcome": outcome, "iterations": state["iterations"] + 1}

        def refine(state: _State) -> dict[str, Any]:
            query = _refined_query(state["message_text"], state["iterations"] - 1)
            try:
                more = retrieve(query)
            except Exception:  # noqa: BLE001 - a retrieval failure just ends the loop with what we have
                logger.warning("deliberation refine retrieval failed; stopping the loop", exc_info=True)
                more = []
            return {"snippets": _merge_snippets(state["snippets"], more)}

        def route(state: _State) -> str:
            if state["iterations"] >= self._max_iterations:
                return END
            if _looks_under_supported(state["outcome"], state["snippets"]):  # type: ignore[arg-type]
                return "refine"
            return END

        builder: StateGraph = StateGraph(_State)
        builder.add_node("reason", reason)
        builder.add_node("refine", refine)
        builder.set_entry_point("reason")
        builder.add_conditional_edges("reason", route, {"refine": "refine", END: END})
        builder.add_edge("refine", "reason")
        graph = builder.compile()

        final: _State = graph.invoke(
            {
                "message_text": message_text,
                "attachments": attachments,
                "snippets": list(initial_snippets),
                "iterations": 0,
                "outcome": None,
            }
        )
        outcome = final["outcome"]
        assert outcome is not None  # the graph always runs `reason` at least once
        logger.info(
            "action=deliberate iterations=%s snippets=%s outcome=%s",
            final["iterations"], len(final["snippets"]), outcome.kind,
        )
        return DeliberationResult(outcome=outcome, snippets_used=final["snippets"], iterations=final["iterations"])
