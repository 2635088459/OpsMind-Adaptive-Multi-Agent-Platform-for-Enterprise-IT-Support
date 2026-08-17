"""SPEC-MK-028 12-observability §"Metrics". Deliberately smoke tests, not exact
exported-value assertions — mirrors agent-runtime-service's own
tests/application/test_telemetry.py exactly, including its own reasoning:
OpenTelemetry's global MeterProvider can only be set once per process (a second
`metrics.set_meter_provider()` call is a documented no-op with a warning), and since
this whole pytest session runs in one process, some other test (e.g. tests/test_app.py's
own `create_app()`) may already have installed the real console-exporting
MeterProvider before this file's own tests run — there is no reliable,
order-independent way to intercept MemoryTelemetry's own exported data points here.
What every method must do, unconditionally, is complete without raising — the real
regression this suite guards against is a typo in an instrument name/label call
crashing the service that calls it.
"""

from __future__ import annotations

import pytest

from memoryknowledge.application.telemetry import MemoryTelemetry

pytestmark = pytest.mark.unit


@pytest.fixture
def telemetry() -> MemoryTelemetry:
    return MemoryTelemetry()


def test_every_search_metric_method_is_callable_without_raising(telemetry: MemoryTelemetry) -> None:
    telemetry.record_search_requested("agent")
    telemetry.record_search_latency(42, "agent")
    telemetry.record_search_degraded("REPOSITORY_UNAVAILABLE")
    telemetry.record_retrieval_outcome(hit=True)
    telemetry.record_retrieval_outcome(hit=False)


def test_every_candidate_metric_method_is_callable_without_raising(telemetry: MemoryTelemetry) -> None:
    telemetry.record_candidate_created("EPISODIC")
    telemetry.record_candidate_approved()
    telemetry.record_candidate_rejected()
    telemetry.record_conflict_detected()


def test_every_graph_metric_method_is_callable_without_raising(telemetry: MemoryTelemetry) -> None:
    telemetry.record_graph_node_created("ENTITY")
    telemetry.record_graph_edge_created("SUPERSEDES")
    telemetry.record_graph_expansion_latency(7.5)
    telemetry.record_graph_path_returned(3)
    telemetry.record_graph_path_returned(0)  # must not add(0) to the counter


def test_every_ingestion_and_outbox_metric_method_is_callable_without_raising(telemetry: MemoryTelemetry) -> None:
    telemetry.record_document_ingestion_latency(120, "success")
    telemetry.record_document_ingestion_latency(15, "failed")
    telemetry.record_embedding_failure("ingest_document")
    telemetry.record_embedding_failure("publish_memory")
    telemetry.set_outbox_backlog(3)
    telemetry.set_outbox_backlog(0)


def test_multiple_instances_do_not_conflict(telemetry: MemoryTelemetry) -> None:
    """Every application service gets its own MemoryTelemetry instance in
    memoryknowledge.container.Container — constructing many must not itself raise,
    even though every instance registers instruments under the same fixed names.
    """
    another = MemoryTelemetry()
    another.record_candidate_created("PROCEDURAL")
