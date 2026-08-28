"""12-observability: composition-root wiring for the OpenTelemetry SDK, mirroring
memory-knowledge-service's own infrastructure/observability.py exactly. This module is
the one place allowed to import the SDK — application.telemetry only ever touches the
vendor-neutral `opentelemetry.metrics` API.

"console" (the default) is genuinely functional, not a stub: it exports real metrics/
spans to stdout, so nothing about running this service locally or under pytest depends
on a collector being reachable. "otlp" is an explicit opt-in for a real collector.
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

from evaluationimprovement.settings import Settings

_configured = False


def configure_observability(settings: Settings) -> None:
    """Idempotent — safe to call more than once (create_app() can run repeatedly
    within one pytest session).
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
