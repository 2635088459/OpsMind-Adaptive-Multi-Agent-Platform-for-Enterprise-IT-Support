# syntax=docker/dockerfile:1
#
# SPEC-XREL-001 event relay sidecar. No hexagonal service, no database, no
# migrations, no HTTP server of its own — just a pika consumer + httpx client —
# so a plain pip install is enough (no uv/lock machinery like the full services).
FROM python:3.13-slim AS runtime

RUN groupadd --system --gid 65532 opsmind \
    && useradd --system --uid 65532 --gid opsmind --home-dir /app --no-create-home opsmind

WORKDIR /app
COPY pyproject.toml ./
COPY src/ src/

RUN pip install --no-cache-dir "pika>=1.3" "httpx>=0.28" "pydantic-settings>=2.5" \
      "opentelemetry-api>=1.27" "opentelemetry-sdk>=1.27" "opentelemetry-exporter-otlp-proto-grpc>=1.27" \
    && pip install --no-cache-dir --no-deps .

# numeric UID so k8s `runAsNonRoot` can verify non-root without inspecting the image
USER 65532

# No listening port. Liveness = the heartbeat file the consumer rewrites on a
# ~5s cadence (see consumer._touch_heartbeat); stale by >30s ⇒ unhealthy.
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import os,time,sys; p=os.environ.get('RELAY_HEARTBEAT_FILE','/tmp/relay-alive'); sys.exit(0 if os.path.exists(p) and time.time()-os.path.getmtime(p) < 30 else 1)"

ENTRYPOINT ["python", "-m", "event_relay"]
