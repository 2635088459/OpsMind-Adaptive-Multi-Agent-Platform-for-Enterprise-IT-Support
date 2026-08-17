"""SPEC-MK-028 12-observability §"Metrics": the exact counter/gauge/histogram names
that section lists, centralized in one class rather than scattered
`meter.create_counter()` calls across every service file — mirrors
agent-runtime-service's own `RuntimeTelemetry` (application/telemetry.py) exactly,
including its own low-cardinality-labels-only rule: never an id (memoryId,
candidateId, retrievalId, documentId) as a label value, only fixed, small-vocabulary
strings (memory_type, requester_type, node_type, degraded_reason).

Uses the OpenTelemetry Metrics API directly (`metrics.get_meter("memoryknowledge")`),
the same "safe to call anywhere, real behavior wired once at the composition root"
shape this codebase already uses for the stdlib `logging` module — see
infrastructure/observability.py's own docstring for the full reasoning.
MemoryTelemetry itself holds no OTel-SDK-specific logic; it only names instruments and
records values through the vendor-neutral API, so it stays a plain application-layer
collaborator (no import-linter violation).

Two of 12-observability's own named metrics do not map onto an obvious counter/
histogram shape, and are resolved here explicitly rather than guessed at silently:

- `memory_retrieval_hit_rate` has no `_total` suffix (unlike every counter this class
  otherwise instruments, all of which do) — the Prometheus naming convention this
  codebase's own metric names already follow elsewhere reserves that suffix for
  monotonic counters, so a "_rate" name is deliberately something else. No LLD text
  specifies how the rate itself should be computed (windowed average? cumulative?), so
  this class records a per-search 1.0/0.0 histogram observation (hit vs. miss) instead
  of inventing a windowing algorithm — the average of that histogram over any query
  window a dashboard picks *is* the hit rate, without this service needing to own that
  windowing decision itself.
- `memory_graph_nodes_total`/`memory_graph_edges_total` — likewise `_total`-suffixed,
  so read as monotonic creation counters ("how many nodes/edges have ever been
  created"), not a live point-in-time graph size (which would need a repository
  COUNT(*) this codebase's ports_out has no method for, and no scan currently owns).
  Incremented once per actual GraphNode/GraphEdge insert (never on a stable-key
  dedup hit that reused an existing row).
"""

from __future__ import annotations

from opentelemetry import metrics
from opentelemetry.metrics import CallbackOptions, Observation

_meter = metrics.get_meter("memoryknowledge")


class MemoryTelemetry:
    def __init__(self) -> None:
        self._search_requests = _meter.create_counter(
            "memory_search_requests_total", description="Memory search requests received"
        )
        self._search_latency = _meter.create_histogram(
            "memory_search_latency_ms", unit="ms", description="Memory search wall-clock latency"
        )
        self._search_degraded = _meter.create_counter(
            "memory_search_degraded_total", description="Memory search requests that returned in a degraded state"
        )
        self._retrieval_hit_rate = _meter.create_histogram(
            "memory_retrieval_hit_rate", description="Per-search hit(1.0)/miss(0.0) observation; average = hit rate"
        )
        self._retrieval_empty = _meter.create_counter(
            "memory_retrieval_empty_total", description="Memory search requests that returned zero results"
        )

        self._candidate_created = _meter.create_counter(
            "memory_candidate_created_total", description="Memory Candidates extracted"
        )
        self._candidate_approved = _meter.create_counter(
            "memory_candidate_approved_total", description="Memory Candidates that reached VALIDATED status"
        )
        self._candidate_rejected = _meter.create_counter(
            "memory_candidate_rejected_total", description="Memory Candidates rejected"
        )
        self._conflict_detected = _meter.create_counter(
            "memory_conflict_detected_total", description="Memory Candidates marked CONFLICTING against active memory"
        )

        self._graph_nodes_created = _meter.create_counter(
            "memory_graph_nodes_total", description="Graph nodes created (excludes stable-key dedup hits)"
        )
        self._graph_edges_created = _meter.create_counter(
            "memory_graph_edges_total", description="Graph edges created (excludes natural-key dedup hits)"
        )
        self._graph_expansion_latency = _meter.create_histogram(
            "memory_graph_expansion_latency_ms", unit="ms", description="Bounded knowledge-graph expansion wall-clock latency"
        )
        self._graph_path_returned = _meter.create_counter(
            "memory_graph_path_returned_total", description="Graph paths returned by expansion"
        )

        self._document_ingestion_latency = _meter.create_histogram(
            "knowledge_document_ingestion_latency_ms", unit="ms", description="Knowledge Document ingestion wall-clock latency"
        )
        self._embedding_failure = _meter.create_counter(
            "knowledge_embedding_failure_total", description="Embedding provider calls that raised"
        )

        # A true async Observable Gauge, not a counter: "memory_outbox_backlog" names a
        # point-in-time depth, not something that accumulates — mirrors
        # agent-runtime-service's own "agent_runtime_outbox_pending" exactly, including
        # its own "lower-bound snapshot as of the last dispatch scan" caveat (see
        # DispatchOutboxEventsService's own docstring for why: rows this call itself
        # just backed off, not a live COUNT(*) query).
        self._outbox_backlog_value = 0
        _meter.create_observable_gauge(
            "memory_outbox_backlog", callbacks=[self._read_outbox_backlog],
            description="Outbox rows still PENDING, as of the last dispatch scan",
        )

    def _read_outbox_backlog(self, options: CallbackOptions):  # noqa: ARG002
        yield Observation(self._outbox_backlog_value)

    # Search metrics -----------------------------------------------------------------
    def record_search_requested(self, requester_type: str) -> None:
        self._search_requests.add(1, {"requester_type": requester_type})

    def record_search_latency(self, latency_ms: int, requester_type: str) -> None:
        self._search_latency.record(latency_ms, {"requester_type": requester_type})

    def record_search_degraded(self, degraded_reason: str) -> None:
        self._search_degraded.add(1, {"degraded_reason": degraded_reason})

    def record_retrieval_outcome(self, hit: bool) -> None:
        self._retrieval_hit_rate.record(1.0 if hit else 0.0)
        if not hit:
            self._retrieval_empty.add(1)

    # Candidate metrics ---------------------------------------------------------------
    def record_candidate_created(self, memory_type: str) -> None:
        self._candidate_created.add(1, {"memory_type": memory_type})

    def record_candidate_approved(self) -> None:
        self._candidate_approved.add(1)

    def record_candidate_rejected(self) -> None:
        self._candidate_rejected.add(1)

    def record_conflict_detected(self) -> None:
        self._conflict_detected.add(1)

    # Graph metrics ---------------------------------------------------------------
    def record_graph_node_created(self, node_type: str) -> None:
        self._graph_nodes_created.add(1, {"node_type": node_type})

    def record_graph_edge_created(self, edge_type: str) -> None:
        self._graph_edges_created.add(1, {"edge_type": edge_type})

    def record_graph_expansion_latency(self, latency_ms: float) -> None:
        self._graph_expansion_latency.record(latency_ms)

    def record_graph_path_returned(self, count: int) -> None:
        if count:
            self._graph_path_returned.add(count)

    # Ingestion / embedding metrics ----------------------------------------------------
    def record_document_ingestion_latency(self, latency_ms: int, outcome: str) -> None:
        self._document_ingestion_latency.record(latency_ms, {"outcome": outcome})

    def record_embedding_failure(self, context: str) -> None:
        self._embedding_failure.add(1, {"context": context})

    def set_outbox_backlog(self, value: int) -> None:
        self._outbox_backlog_value = value
