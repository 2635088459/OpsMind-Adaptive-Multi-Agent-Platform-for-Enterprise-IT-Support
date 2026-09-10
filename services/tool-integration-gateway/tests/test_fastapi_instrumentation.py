"""SPEC-XOBS-001 Part B: the FastAPI app emits a per-request SERVER span so a tool
request arriving from agent-runtime shows up in the Tempo waterfall (previously only
hand-written domain spans existed here).
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from tool_gateway.main import create_app


@pytest.fixture()
def span_exporter(monkeypatch: pytest.MonkeyPatch) -> InMemorySpanExporter:
    # A dedicated provider for this test — configure_observability() already set the
    # process-wide one; instrument_app binds to whatever the global provider is at
    # request time, so swap it here and restore after.
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    original = trace.get_tracer_provider()
    trace._TRACER_PROVIDER = provider  # noqa: SLF001 - test-only override
    try:
        yield exporter
    finally:
        trace._TRACER_PROVIDER = original  # noqa: SLF001


def test_a_request_produces_a_server_span(span_exporter: InMemorySpanExporter) -> None:
    client = TestClient(create_app())
    response = client.get("/health")
    assert response.status_code == 200

    spans = span_exporter.get_finished_spans()
    server_spans = [s for s in spans if s.kind is trace.SpanKind.SERVER]
    assert server_spans, f"expected a SERVER span, got {[(s.name, s.kind) for s in spans]}"
    assert any(s.attributes.get("http.route") == "/health" for s in server_spans)
