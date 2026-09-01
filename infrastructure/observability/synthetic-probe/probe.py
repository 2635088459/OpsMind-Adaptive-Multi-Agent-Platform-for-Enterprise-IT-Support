#!/usr/bin/env python3
"""Synthetic pipeline probe (SPEC-OP-033).

Continuously proves the FULL real pipeline (OTLP -> Collector -> Tempo)
works — not at CI/manual-smoke time, but every PROBE_INTERVAL_SECONDS,
independent of any human running scripts/observability-stack.sh. Exposes
its own pass/fail + latency as a Prometheus text-format /metrics endpoint,
scraped like every other component in this stack (SPEC-OP-012 file_sd).

Not a new "custom telemetry backend" (ADR-0002) — it is a PRODUCER, using
the exact same real ingestion boundary (OTLP/HTTPS + bearer auth, ADR-0007)
every other signal in this stack goes through, and a CONSUMER querying
Tempo's own real query API — nothing here bypasses the Collector or talks
to a storage backend directly for writes.

Pure standard library. Python 3.9+.
"""
from __future__ import annotations

import json
import os
import ssl
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

OTLP_ENDPOINT = os.environ.get("OTLP_TRACES_ENDPOINT", "https://otel-collector:4318/v1/traces")
TEMPO_QUERY_BASE = os.environ.get("TEMPO_QUERY_BASE", "http://tempo:3200")
AUTH_TOKEN = os.environ.get("OTEL_GATEWAY_AUTH_TOKEN", "")
TENANT = os.environ.get("PROBE_TENANT", "observability-platform")
INTERVAL_SECONDS = float(os.environ.get("PROBE_INTERVAL_SECONDS", "60"))
QUERY_WAIT_SECONDS = float(os.environ.get("PROBE_QUERY_WAIT_SECONDS", "13"))  # > tail_sampling decision_wait
METRICS_PORT = int(os.environ.get("PROBE_METRICS_PORT", "9464"))
# Local dev only: the Collector's own TLS cert is self-signed (ADR-0007). A
# production overlay would point this at a real CA and set this False.
INSECURE_TLS = os.environ.get("PROBE_INSECURE_TLS", "true").lower() == "true"

_state_lock = threading.Lock()
_state = {
    "runs_total": 0,
    "failures_total": 0,
    "last_success": 0,  # 1 or 0, gauge
    "last_duration_seconds": 0.0,
    "last_run_unix": 0,
}


def _ssl_context() -> ssl.SSLContext | None:
    if not OTLP_ENDPOINT.startswith("https://"):
        return None
    ctx = ssl.create_default_context()
    if INSECURE_TLS:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    return ctx


def _push_trace(trace_id: str, span_id: str) -> None:
    ts = int(time.time() * 1e9)
    start = ts - 20_000_000  # 20ms synthetic span
    body = {
        "resourceSpans": [{
            "resource": {"attributes": [
                {"key": "service.name", "value": {"stringValue": "synthetic-probe"}},
                {"key": "service.namespace", "value": {"stringValue": TENANT}},
            ]},
            "scopeSpans": [{"scope": {"name": "synthetic-probe"}, "spans": [{
                "traceId": trace_id, "spanId": span_id,
                "name": "synthetic-probe-roundtrip", "kind": 2,
                "startTimeUnixNano": str(start), "endTimeUnixNano": str(ts),
                "attributes": [{"key": "opsmind.smoke_test", "value": {"stringValue": "true"}}],
                "status": {"code": 1},
            }]}],
        }],
    }
    req = urllib.request.Request(
        OTLP_ENDPOINT, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {AUTH_TOKEN}",
        },
    )
    with urllib.request.urlopen(req, context=_ssl_context(), timeout=10) as resp:
        if resp.status >= 300:
            raise RuntimeError(f"push failed: HTTP {resp.status}")


def _query_trace(trace_id: str) -> bool:
    url = f"{TEMPO_QUERY_BASE}/api/traces/{trace_id}"
    req = urllib.request.Request(url, headers={"X-Scope-OrgID": TENANT})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status == 200
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise


def _run_once() -> bool:
    trace_id = uuid.uuid4().hex
    span_id = uuid.uuid4().hex[:16]
    _push_trace(trace_id, span_id)
    time.sleep(QUERY_WAIT_SECONDS)
    return _query_trace(trace_id)


def _probe_loop() -> None:
    while True:
        start = time.time()
        ok = False
        try:
            ok = _run_once()
        except Exception as e:  # noqa: BLE001 — a probe failure is data, never fatal
            print(f"synthetic-probe: run failed: {e}", flush=True)
        duration = time.time() - start
        with _state_lock:
            _state["runs_total"] += 1
            if not ok:
                _state["failures_total"] += 1
            _state["last_success"] = 1 if ok else 0
            _state["last_duration_seconds"] = duration
            _state["last_run_unix"] = int(time.time())
        print(f"synthetic-probe: run complete ok={ok} duration={duration:.2f}s", flush=True)
        time.sleep(max(0.0, INTERVAL_SECONDS - duration))


class MetricsHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 — BaseHTTPRequestHandler's own naming
        if self.path != "/metrics":
            self.send_response(404)
            self.end_headers()
            return
        with _state_lock:
            s = dict(_state)
        lines = [
            "# HELP synthetic_probe_runs_total Total synthetic pipeline probe runs.",
            "# TYPE synthetic_probe_runs_total counter",
            f"synthetic_probe_runs_total {s['runs_total']}",
            "# HELP synthetic_probe_failures_total Total failed synthetic pipeline probe runs.",
            "# TYPE synthetic_probe_failures_total counter",
            f"synthetic_probe_failures_total {s['failures_total']}",
            "# HELP synthetic_probe_last_success Whether the most recent probe run succeeded (1) or not (0).",
            "# TYPE synthetic_probe_last_success gauge",
            f"synthetic_probe_last_success {s['last_success']}",
            "# HELP synthetic_probe_last_duration_seconds Wall-clock duration of the most recent probe run.",
            "# TYPE synthetic_probe_last_duration_seconds gauge",
            f"synthetic_probe_last_duration_seconds {s['last_duration_seconds']}",
            "# HELP synthetic_probe_last_run_unix Unix timestamp of the most recent probe run.",
            "# TYPE synthetic_probe_last_run_unix gauge",
            f"synthetic_probe_last_run_unix {s['last_run_unix']}",
            "",
        ]
        payload = "\n".join(lines).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt: str, *args) -> None:  # noqa: A002 — silence per-request access logs
        pass


def main() -> None:
    threading.Thread(target=_probe_loop, daemon=True).start()
    HTTPServer(("0.0.0.0", METRICS_PORT), MetricsHandler).serve_forever()


if __name__ == "__main__":
    main()
