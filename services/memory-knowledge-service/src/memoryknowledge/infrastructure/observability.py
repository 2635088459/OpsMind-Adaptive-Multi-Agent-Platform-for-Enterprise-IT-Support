"""SPEC-MK-028 12-observability: composition-root wiring for the OpenTelemetry SDK,
mirroring agent-runtime-service's own infrastructure/observability.py exactly (field
names, exporter modes, idempotency). This module is the one place allowed to import
the SDK (as opposed to application/telemetry.py, which only ever touches the
vendor-neutral `opentelemetry.metrics`/`opentelemetry.trace` API) — the same
composition-root discipline memoryknowledge.container already applies to concrete
adapters.

"console" (the default) is genuinely functional, not a stub: it exports real
metrics/spans to stdout on a 5s interval, so nothing about running this service
locally or under pytest depends on a collector being reachable. "otlp" is an explicit
opt-in (Settings.otel_exporter) for a real collector endpoint — same posture as
agent-runtime-service's own SPEC-ARO-034.
"""

from __future__ import annotations

from opentelemetry import metrics, trace
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import ConsoleMetricExporter, PeriodicExportingMetricReader
from opentelemetry.sdk.resources import SERVICE_NAME, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, ConsoleSpanExporter, SimpleSpanProcessor

from memoryknowledge.settings import Settings

_configured = False


def configure_observability(settings: Settings) -> None:
    """Idempotent — safe to call more than once (create_app() can run repeatedly
    within one pytest session, e.g. TestClient(create_app()) per test). The global
    tracer/meter providers are process-wide OpenTelemetry state, not something this
    module can scope to a single FastAPI app instance.
    """
    global _configured
    if _configured:
        return
    _configured = True

    resource = Resource.create({SERVICE_NAME: settings.otel_service_name})

    tracer_provider = TracerProvider(resource=resource)
    if settings.otel_exporter == "otlp":
        tracer_provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=settings.otel_exporter_otlp_endpoint, insecure=True))
        )
        metric_reader = PeriodicExportingMetricReader(
            OTLPMetricExporter(endpoint=settings.otel_exporter_otlp_endpoint, insecure=True)
        )
    else:
        tracer_provider.add_span_processor(SimpleSpanProcessor(ConsoleSpanExporter()))
        metric_reader = PeriodicExportingMetricReader(ConsoleMetricExporter(), export_interval_millis=5000)

    meter_provider = MeterProvider(resource=resource, metric_readers=[metric_reader])

    trace.set_tracer_provider(tracer_provider)
    metrics.set_meter_provider(meter_provider)
