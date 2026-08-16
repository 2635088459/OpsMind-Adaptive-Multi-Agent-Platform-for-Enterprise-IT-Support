"""01-domain-model §"RetrievalLog": an auditable record of every retrieval.
02-business-invariants §"检索不变量": every entry is written whether or not the
retrieval degraded, so operators can always see what evidence (if any) an Agent saw.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Mapping

from memoryknowledge.domain.ids import RetrievalId, TicketCycleId, TicketId, WorkflowInstanceId
from memoryknowledge.domain.values import GraphPath, RetrievalScore


def score_text_relevance(query: str, text: str, *, recency: float = 0.0, trust: float = 0.0) -> RetrievalScore:
    """13-package-and-class-design: "Retrieval scorer 与 redactor 可单测" — pure, no I/O,
    so it lives in domain rather than behind a port (unlike EmbeddingProvider, which
    needs a real model/API and so must be a port). A token-overlap (Jaccard) proxy for
    both the semantic and keyword sub-scores: SPEC-MK-001's own retrieval scope is
    "boundaries", not the real pgvector similarity search phase-05 (retrieval-and-
    knowledge-graph) builds — this keeps SearchMemoryService's contract (score must
    combine more than one signal, per 02-business-invariants) real and testable without
    fabricating a trained model's output.
    """

    query_tokens = {t for t in query.lower().split() if t}
    text_tokens = {t for t in text.lower().split() if t}
    if not query_tokens or not text_tokens:
        overlap = 0.0
    else:
        overlap = len(query_tokens & text_tokens) / len(query_tokens | text_tokens)
    return RetrievalScore(semantic=overlap, keyword=overlap, recency=recency, trust=trust)


@dataclass(frozen=True, slots=True)
class RetrievalLog:
    retrieval_id: RetrievalId
    requester_type: str
    requester_id: str
    ticket_id: TicketId | None
    ticket_cycle_id: TicketCycleId | None
    workflow_instance_id: WorkflowInstanceId | None
    query_hash: str
    filters: Mapping[str, str]
    result_refs: tuple[str, ...]
    graph_paths: tuple[GraphPath, ...]
    degraded: bool
    latency_ms: int
    created_at: datetime

    @staticmethod
    def record(
        retrieval_id: RetrievalId, requester_type: str, requester_id: str, query_hash: str,
        result_refs: tuple[str, ...], degraded: bool, latency_ms: int, created_at: datetime,
        ticket_id: TicketId | None = None, ticket_cycle_id: TicketCycleId | None = None,
        workflow_instance_id: WorkflowInstanceId | None = None,
        filters: Mapping[str, str] | None = None, graph_paths: tuple[GraphPath, ...] = (),
    ) -> "RetrievalLog":
        """10-failure-handling (deferred detail, mandated here by phase-00's own
        constraint): "Memory unavailable 时 ... 不得因为检索不可用而伪造历史证据" — degraded
        retrievals still get a log entry with an honest, possibly-empty result_refs, never
        a fabricated result.
        """
        return RetrievalLog(
            retrieval_id=retrieval_id, requester_type=requester_type, requester_id=requester_id,
            ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id, workflow_instance_id=workflow_instance_id,
            query_hash=query_hash, filters=dict(filters or {}), result_refs=result_refs, graph_paths=graph_paths,
            degraded=degraded, latency_ms=latency_ms, created_at=created_at,
        )
