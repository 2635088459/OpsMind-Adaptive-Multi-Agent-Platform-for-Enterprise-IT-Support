#!/usr/bin/env sh
# OpsMind Observability Platform — local stack wrapper (SPEC-OP-002).
#
# Assembles the base + local-overlay --env-file list and drives the Compose stack
# in infrastructure/docker-compose/observability-stack.yml.
#
#   scripts/observability-stack.sh config   # render + validate merged compose
#   scripts/observability-stack.sh up       # start, wait for health
#   scripts/observability-stack.sh smoke    # up + push a real signal + query it back
#                                           #   (+ SPEC-OP-003: assert deny-listed
#                                           #    attribute keys are stripped)
#   scripts/observability-stack.sh down     # stop + remove volumes
#   scripts/observability-stack.sh ps|logs
#
# Pure POSIX sh + docker + curl. No secrets. Telemetry pushed by `smoke` is synthetic.
set -eu

REPO="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OBS="$REPO/infrastructure/observability"
COMPOSE_FILE="$REPO/infrastructure/docker-compose/observability-stack.yml"

ENV_FILES="--env-file $OBS/versions.env \
--env-file $OBS/prometheus/overlays/local/values.env \
--env-file $OBS/alertmanager/overlays/local/values.env"

# shellcheck disable=SC2086
dc() { docker compose $ENV_FILES -f "$COMPOSE_FILE" "$@"; }

TRACE_ID="0af7651916cd43dd8448eb211c80319c"
SPAN_ID="b7ad6b7169203331"
# Second trace: resource is missing service.name — SPEC-OP-004 transform/resource-contract
# must substitute unknown_service and stamp opsmind.resource.violation.
VIOL_TRACE_ID="11111111111111111111111111111111"
VIOL_SPAN_ID="2222222222222222"
# Third trace: a publish->consume hand-off (SPEC-OP-005). PRODUCER span + CONSUMER child
# in one trace; consumer carries baggage.* attributes that transform/baggage-contract
# must strip while keeping the plain correlation_id.
PROP_TRACE_ID="33333333333333333333333333333333"
PROP_PROD_SPAN="4444444444444444"
PROP_CONS_SPAN="5555555555555555"

now_ns() { echo "$(date +%s)000000000"; }

push_signal() {
  ts="$(now_ns)"
  start="$(( ${ts%??????????} - 1 ))000000000"
  # The span/log below deliberately carry deny-listed keys (authorization, password,
  # api_key) alongside a legit one (correlation_id). SPEC-OP-003's transform/governance
  # processor must strip the deny-listed keys before export; query_back asserts that.
  echo "→ pushing OTLP trace (trace_id=$TRACE_ID) — conformant resource + deny-listed span attrs"
  curl -sf -o /dev/null -X POST http://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-002-smoke"}},
      {"key":"service.version","value":{"stringValue":"0.0.1"}},
      {"key":"service.namespace","value":{"stringValue":"observability-platform"}},
      {"key":"deployment.environment","value":{"stringValue":"local"}},
      {"key":"service.instance.id","value":{"stringValue":"smoke/1"}},
      {"key":"telemetry.sdk.language","value":{"stringValue":"python"}}]},
      "scopeSpans":[{"scope":{"name":"op-002-smoke"},"spans":[{
        "traceId":"'"$TRACE_ID"'","spanId":"'"$SPAN_ID"'",
        "name":"op-002-smoke-span","kind":2,
        "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
        "attributes":[
          {"key":"correlation_id","value":{"stringValue":"op-002"}},
          {"key":"authorization","value":{"stringValue":"Bearer SHOULD-BE-STRIPPED"}},
          {"key":"password","value":{"stringValue":"SHOULD-BE-STRIPPED"}},
          {"key":"api_key","value":{"stringValue":"SHOULD-BE-STRIPPED"}}],
        "status":{"code":1}}]}]}]}'

  echo "→ pushing OTLP trace (trace_id=$VIOL_TRACE_ID) — resource MISSING service.name (SPEC-OP-004)"
  curl -sf -o /dev/null -X POST http://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.version","value":{"stringValue":"0.0.1"}},
      {"key":"deployment.environment","value":{"stringValue":"local"}}]},
      "scopeSpans":[{"scope":{"name":"op-002-smoke"},"spans":[{
        "traceId":"'"$VIOL_TRACE_ID"'","spanId":"'"$VIOL_SPAN_ID"'",
        "name":"op-004-violation-span","kind":2,
        "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
        "status":{"code":1}}]}]}]}'

  echo "→ pushing OTLP trace (trace_id=$PROP_TRACE_ID) — PRODUCER + CONSUMER child with baggage.* attrs (SPEC-OP-005)"
  curl -sf -o /dev/null -X POST http://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-005-smoke"}},
      {"key":"service.version","value":{"stringValue":"0.0.1"}},
      {"key":"service.namespace","value":{"stringValue":"observability-platform"}},
      {"key":"deployment.environment","value":{"stringValue":"local"}},
      {"key":"service.instance.id","value":{"stringValue":"smoke/1"}},
      {"key":"telemetry.sdk.language","value":{"stringValue":"python"}}]},
      "scopeSpans":[{"scope":{"name":"op-005-smoke"},"spans":[
        {"traceId":"'"$PROP_TRACE_ID"'","spanId":"'"$PROP_PROD_SPAN"'",
         "name":"ticket.created publish","kind":4,
         "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'","status":{"code":1}},
        {"traceId":"'"$PROP_TRACE_ID"'","spanId":"'"$PROP_CONS_SPAN"'","parentSpanId":"'"$PROP_PROD_SPAN"'",
         "name":"ticket.created process","kind":5,
         "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
         "attributes":[
           {"key":"correlation_id","value":{"stringValue":"INC-2048"}},
           {"key":"baggage.correlation_id","value":{"stringValue":"INC-2048"}},
           {"key":"baggage.authorization","value":{"stringValue":"Bearer SHOULD-BE-STRIPPED"}}],
         "status":{"code":1}}]}]}]}'

  echo "→ pushing OTLP log line to collector :4318"
  curl -sf -o /dev/null -X POST http://localhost:4318/v1/logs \
    -H 'Content-Type: application/json' -d '{
    "resourceLogs":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-002-smoke"}}]},
      "scopeLogs":[{"scope":{"name":"op-002-smoke"},"logRecords":[{
        "timeUnixNano":"'"$ts"'","severityText":"INFO",
        "body":{"stringValue":"op-002 smoke log"},
        "attributes":[{"key":"trace_id","value":{"stringValue":"'"$TRACE_ID"'"}}]}]}]}]}'

  echo "→ pushing OTLP metric to collector :4318 (with forbidden labels ticket_id / run_id — SPEC-OP-006)"
  curl -sf -o /dev/null -X POST http://localhost:4318/v1/metrics \
    -H 'Content-Type: application/json' -d '{
    "resourceMetrics":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-002-smoke"}}]},
      "scopeMetrics":[{"scope":{"name":"op-002-smoke"},"metrics":[{
        "name":"op_002_smoke_total","unit":"1",
        "sum":{"aggregationTemporality":2,"isMonotonic":true,"dataPoints":[
          {"asInt":"1","timeUnixNano":"'"$ts"'",
           "attributes":[
             {"key":"case","value":{"stringValue":"smoke"}},
             {"key":"ticket_id","value":{"stringValue":"INC-9999"}},
             {"key":"run_id","value":{"stringValue":"r-abc123"}}]}]}}]}]}]}'
}

query_back() {
  rc=0
  echo "→ waiting 15s for pipeline flush"; sleep 15

  echo "→ Tempo: GET /api/traces/$TRACE_ID"
  trace_json="$(curl -sf "http://localhost:3200/api/traces/$TRACE_ID" || true)"
  if printf '%s' "$trace_json" | grep -q "op-002-smoke-span"; then
    echo "  ✓ trace found in Tempo with expected span name"
  else
    echo "  ✗ trace NOT found in Tempo"; rc=1
  fi
  if printf '%s' "$trace_json" | grep -q "correlation_id"; then
    echo "  ✓ legit attribute 'correlation_id' preserved"
  else
    echo "  ✗ legit attribute 'correlation_id' missing"; rc=1
  fi
  echo "→ SPEC-OP-003 governance: deny-listed keys must be stripped"
  if printf '%s' "$trace_json" | grep -Eq '"key":"(authorization|password|api_key)"'; then
    echo "  ✗ a deny-listed attribute key survived to Tempo"; rc=1
  else
    echo "  ✓ authorization / password / api_key stripped by transform/governance"
  fi
  echo "→ SPEC-OP-004: conformant resource attributes preserved"
  if printf '%s' "$trace_json" | grep -q '"observability-platform"' \
     && printf '%s' "$trace_json" | grep -q "service.instance.id"; then
    echo "  ✓ service.namespace / service.instance.id present on the Resource"
  else
    echo "  ✗ conformant resource attributes missing in Tempo"; rc=1
  fi

  echo "→ SPEC-OP-004: missing service.name is stamped, not dropped"
  viol_json="$(curl -sf "http://localhost:3200/api/traces/$VIOL_TRACE_ID" || true)"
  if printf '%s' "$viol_json" | grep -q "op-004-violation-span"; then
    echo "  ✓ the non-conformant trace was still ingested (ADR-0004)"
  else
    echo "  ✗ non-conformant trace was dropped"; rc=1
  fi
  if printf '%s' "$viol_json" | grep -q "unknown_service" \
     && printf '%s' "$viol_json" | grep -q "missing:service.name"; then
    echo "  ✓ service.name=unknown_service + opsmind.resource.violation stamped by transform/resource-contract"
  else
    echo "  ✗ resource-contract did not stamp the violation"; rc=1
  fi

  echo "→ SPEC-OP-005: publish→consume hand-off is one linked trace, baggage.* stripped"
  prop_json="$(curl -sf "http://localhost:3200/api/traces/$PROP_TRACE_ID" || true)"
  # Tempo returns span ids base64-encoded; assert the child's parentSpanId points at a
  # span present in the same trace (i.e. the producer span).
  child_parent="$(printf '%s' "$prop_json" | grep -o '"parentSpanId":"[A-Za-z0-9+/=]\{1,\}"' | head -1 | sed 's/.*:"//;s/"$//')"
  if printf '%s' "$prop_json" | grep -q "ticket.created publish" \
     && printf '%s' "$prop_json" | grep -q "ticket.created process" \
     && [ -n "$child_parent" ] \
     && printf '%s' "$prop_json" | grep -q "\"spanId\":\"$child_parent\""; then
    echo "  ✓ PRODUCER + CONSUMER child in one trace (consumer parent resolves to the producer span)"
  else
    echo "  ✗ publish→consume not linked as one trace"; rc=1
  fi
  if printf '%s' "$prop_json" | grep -q '"key":"baggage\.'; then
    echo "  ✗ a baggage.* attribute survived to Tempo"; rc=1
  else
    echo "  ✓ baggage.correlation_id / baggage.authorization stripped by transform/baggage-contract"
  fi
  if printf '%s' "$prop_json" | grep -q '"key":"correlation_id"'; then
    echo "  ✓ plain correlation_id attribute preserved"
  else
    echo "  ✗ plain correlation_id was lost"; rc=1
  fi

  echo "→ Prometheus: query op_002_smoke_total"
  metric_json="$(curl -sf 'http://localhost:9090/api/v1/query?query=op_002_smoke_total' || true)"
  if printf '%s' "$metric_json" | grep -q '"status":"success"' \
     && printf '%s' "$metric_json" | grep -q '"result":\[{'; then
    echo "  ✓ metric queryable in Prometheus"
  else
    echo "  ✗ metric NOT queryable in Prometheus"; rc=1
  fi
  echo "→ SPEC-OP-006: forbidden metric labels must be stripped before Prometheus"
  if printf '%s' "$metric_json" | grep -Eq '"(ticket_id|run_id)":'; then
    echo "  ✗ a forbidden label (ticket_id/run_id) reached Prometheus"; rc=1
  else
    echo "  ✓ ticket_id / run_id stripped by transform/metric-cardinality"
  fi

  echo "→ Prometheus: recording rule job:up:ratio present"
  curl -sf "http://localhost:9090/api/v1/query?query=job:up:ratio" | grep -q '"status":"success"' \
    && echo "  ✓ recording rule evaluated" || { echo "  ✗ recording rule missing"; rc=1; }

  echo "→ Loki: query {service_name=\"op-002-smoke\"}"
  end="$(date +%s)000000000"; begin="$(( $(date +%s) - 600 ))000000000"
  if curl -sf --data-urlencode 'query={service_name="op-002-smoke"}' \
       --data-urlencode "start=$begin" --data-urlencode "end=$end" \
       "http://localhost:3100/loki/api/v1/query_range" | grep -q "op-002 smoke log"; then
    echo "  ✓ log line found in Loki (correlatable by trace_id)"
  else
    echo "  ✗ log line NOT found in Loki"; rc=1
  fi

  echo "→ Alertmanager: config loaded"
  curl -sf "http://localhost:9093/api/v2/status" | grep -q '"cluster"' \
    && echo "  ✓ alertmanager up" || { echo "  ✗ alertmanager status failed"; rc=1; }

  return $rc
}

case "${1:-}" in
  config)  dc config >/dev/null && echo "compose config OK: $COMPOSE_FILE" ;;
  up)      dc up -d --wait && dc ps ;;
  down)    dc down -v --remove-orphans ;;
  ps)      dc ps ;;
  logs)    shift; dc logs "$@" ;;
  smoke)
    dc up -d --wait
    dc ps
    push_signal
    if query_back; then
      echo; echo "SMOKE: PASS"; exit 0
    else
      echo; echo "SMOKE: FAIL"; exit 1
    fi
    ;;
  *)
    echo "usage: $0 {config|up|smoke|down|ps|logs}" >&2
    exit 2
    ;;
esac
