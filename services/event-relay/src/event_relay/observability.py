"""SPEC-XREL-001 §Observability: configure the process-wide OpenTelemetry
TracerProvider / MeterProvider once, mirroring every other Python service's own
`configure_observability` (field names, exporter modes, idempotency).

`otel_exporter="console"` (default) is genuinely functional — spans/metrics to stdout
on a 5s interval — so nothing about running the relay locally or under pytest depends
on a collector being reachable. `otel_exporter="otlp"` ships to a real collector at
`otel_exporter_otlp_endpoint`.
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

from event_relay.settings import Settings

_configured = False


def configure_observability(settings: Settings) -> None:
    """Idempotent — safe to call more than once (tests build RelayConsumer repeatedly
    in one process); the global providers are process-wide OpenTelemetry state.
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
