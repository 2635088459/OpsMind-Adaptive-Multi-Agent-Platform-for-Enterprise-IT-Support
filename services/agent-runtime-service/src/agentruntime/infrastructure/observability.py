"""SPEC-ARO-034 12-observability: configures the global OpenTelemetry
TracerProvider/MeterProvider exactly once, mirroring main._configure_logging()'s own
"call once from create_app(), everything else just uses the stdlib API" shape
(SPEC-ARO-026). Application services never construct a Tracer/Meter themselves or
depend on this module directly — they call `opentelemetry.trace.get_tracer(__name__)`/
`opentelemetry.metrics.get_meter(__name__)`, the same "safe to import anywhere, real
behavior wired once at the composition root" pattern this codebase already uses for
the stdlib `logging` module (`logger = logging.getLogger(__name__)` in every service
file, real handler/level configured once in main.py). This keeps agentruntime.domain
and agentruntime.application free of any dependency on *this* module — only on the
OpenTelemetry API package itself, which — like `logging` — is not "infrastructure" in
this codebase's own layering sense (import-linter's "Application must not depend on
infrastructure" contract is about this codebase's own agentruntime.infrastructure
package, not third-party libraries every layer already uses, e.g. stdlib logging).

settings.otel_exporter picks the backend: "console" (default, see settings.py's own
docstring for why) logs each span/metric locally via ConsoleSpanExporter/
ConsoleMetricExporter — genuinely functional, not a no-op stub, mirroring
LoggingEventPublisherAdapter's own "still real, just local" stance. "otlp" ships to a
real Collector at settings.otel_exporter_otlp_endpoint once one exists.
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

from agentruntime.settings import Settings

_configured = False


def configure_observability(settings: Settings) -> None:
    """Idempotent — safe to call more than once (tests that rebuild the container
    repeatedly via get_container.cache_clear() must not accumulate a fresh
    TracerProvider/exporter on every rebuild).
    """
    global _configured
    if _configured:
        return
    _configured = True

    resource = Resource.create({SERVICE_NAME: settings.otel_service_name})

    tracer_provider = TracerProvider(resource=resource)
    meter_provider: MeterProvider
    if settings.otel_exporter == "otlp":
        tracer_provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=settings.otel_exporter_otlp_endpoint, insecure=True)))
        metric_reader = PeriodicExportingMetricReader(
            OTLPMetricExporter(endpoint=settings.otel_exporter_otlp_endpoint, insecure=True)
        )
    else:
        # SimpleSpanProcessor, not Batch: console output should appear immediately
        # (matters for local dev and for this spec's own E2E log-grepping, the same
        # reason SPEC-ARO-025/026 never buffer their own log lines).
        tracer_provider.add_span_processor(SimpleSpanProcessor(ConsoleSpanExporter()))
        metric_reader = PeriodicExportingMetricReader(ConsoleMetricExporter(), export_interval_millis=5000)

    meter_provider = MeterProvider(resource=resource, metric_readers=[metric_reader])

    trace.set_tracer_provider(tracer_provider)
    metrics.set_meter_provider(meter_provider)
