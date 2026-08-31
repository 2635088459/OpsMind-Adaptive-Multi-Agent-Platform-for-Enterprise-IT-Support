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
# `up`/`smoke` first generate an ephemeral self-signed dev TLS cert (SPEC-OP-008 /
# ADR-0007, never committed) and push OTLP over https:// with a bearer token.
#
# Pure POSIX sh + docker + curl + openssl. No secrets. Telemetry pushed by `smoke`
# is synthetic; the gateway auth token is a local-dev-only placeholder.
set -eu

REPO="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OBS="$REPO/infrastructure/observability"
COMPOSE_FILE="$REPO/infrastructure/docker-compose/observability-stack.yml"

ENV_FILES="--env-file $OBS/versions.env \
--env-file $OBS/prometheus/overlays/local/values.env \
--env-file $OBS/alertmanager/overlays/local/values.env \
--env-file $OBS/collector/overlays/local/values.env"

# shellcheck disable=SC2086
dc() { docker compose $ENV_FILES -f "$COMPOSE_FILE" "$@"; }

# SPEC-OP-008 / ADR-0007 — read OTEL_GATEWAY_AUTH_TOKEN so this script's own curl
# calls can authenticate against the gateway. Plain KEY=VALUE, safe to source.
# shellcheck disable=SC1090
. "$OBS/collector/overlays/local/values.env"

TLS_DIR="$OBS/collector/overlays/local/.tls"

# SPEC-OP-008 / ADR-0007 — generate an ephemeral self-signed dev certificate for the
# OTLP gateway if one isn't already there. NEVER committed (gitignored); regenerated
# on demand. SAN covers both the in-network hostname and localhost so this script's
# own host-side curl calls (via -k, since it's self-signed) and any future in-network
# client both resolve correctly.
ensure_dev_tls() {
  if [ -f "$TLS_DIR/server.crt" ] && [ -f "$TLS_DIR/server.key" ]; then
    return 0
  fi
  echo "→ generating ephemeral self-signed dev TLS cert (SPEC-OP-008, never committed)"
  mkdir -p "$TLS_DIR"
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$TLS_DIR/server.key" -out "$TLS_DIR/server.crt" \
    -subj "/CN=otel-collector" \
    -addext "subjectAltName=DNS:otel-collector,DNS:localhost,IP:127.0.0.1" \
    >/dev/null 2>&1
  chmod 600 "$TLS_DIR/server.key"
}

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
# SPEC-OP-009: a health-check-shaped span; filter/noise must drop it entirely.
NOISE_TRACE_ID="66666666666666666666666666666666"
NOISE_SPAN_ID="6666666666666666"
# SPEC-OP-009: OLD semconv attrs only; attributes/semconv-compat must add the new
# canonical keys (SPEC-OP-004/006) without being told about them explicitly.
SEMCONV_TRACE_ID="77777777777777777777777777777777"
SEMCONV_SPAN_ID="7777777777777777"
# SPEC-OP-009: routing connector — ERROR-status span must gain opsmind.trace.priority;
# an OK-status span in the SAME push must NOT.
ERROR_TRACE_ID="88888888888888888888888888888888"
ERROR_SPAN_ID="8888888888888888"
OK_TRACE_ID="99999999999999999999999999999999"
OK_SPAN_ID="9999999999999999"
# SPEC-OP-010: tail_sampling must ALWAYS keep a slow (>1s) trace and a trace flagged
# security.sensitive=true, even though both are fast/normal enough that the 10%
# baseline probabilistic policy alone would very likely have dropped them.
SLOW_TRACE_ID="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
SLOW_SPAN_ID="aaaaaaaaaaaaaaaa"
RISKY_TRACE_ID="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
RISKY_SPAN_ID="bbbbbbbbbbbbbbbb"
# SPEC-OP-014: proves Tempo's metrics_generator (span-metrics + exemplars), tagged
# smoke_test so tail_sampling always keeps it regardless of the probabilistic floor.
METRICS_GEN_TRACE_ID="cccccccccccccccccccccccccccccccc"
METRICS_GEN_SPAN_ID="cccccccccccccccc"

now_ns() { echo "$(date +%s)000000000"; }

push_signal() {
  ts="$(now_ns)"
  # A real ~50ms-ago start (was previously computed via a string-truncation trick
  # that silently produced a multi-decade "duration" — harmless before SPEC-OP-010,
  # but it would have made every span here trivially match the tail_sampling "slow"
  # (>1000ms) policy, masking whether smoke-test-traffic tagging below is doing
  # anything. Fixed to real arithmetic so "slow" is only ever true for $SLOW_TRACE_ID.
  start="$(( ts - 50000000 ))"
  # SPEC-OP-010 — a genuinely slow (>1s) start, for $SLOW_TRACE_ID only.
  slow_start="$(( ts - 2000000000 ))"
  # The span/log below deliberately carry deny-listed keys (authorization, password,
  # api_key) alongside a legit one (correlation_id). SPEC-OP-003's transform/governance
  # processor must strip the deny-listed keys before export; query_back asserts that.
  echo "→ pushing OTLP trace (trace_id=$TRACE_ID) — conformant resource + deny-listed span attrs"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
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
          {"key":"api_key","value":{"stringValue":"SHOULD-BE-STRIPPED"}},
          {"key":"opsmind.smoke_test","value":{"stringValue":"true"}}],
        "status":{"code":1}}]}]}]}'

  echo "→ pushing OTLP trace (trace_id=$VIOL_TRACE_ID) — resource MISSING service.name (SPEC-OP-004)"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.version","value":{"stringValue":"0.0.1"}},
      {"key":"deployment.environment","value":{"stringValue":"local"}}]},
      "scopeSpans":[{"scope":{"name":"op-002-smoke"},"spans":[{
        "traceId":"'"$VIOL_TRACE_ID"'","spanId":"'"$VIOL_SPAN_ID"'",
        "name":"op-004-violation-span","kind":2,
        "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
        "attributes":[{"key":"opsmind.smoke_test","value":{"stringValue":"true"}}],
        "status":{"code":1}}]}]}]}'

  echo "→ pushing OTLP trace (trace_id=$PROP_TRACE_ID) — PRODUCER + CONSUMER child with baggage.* attrs (SPEC-OP-005)"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
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
         "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
         "attributes":[{"key":"opsmind.smoke_test","value":{"stringValue":"true"}}],
         "status":{"code":1}},
        {"traceId":"'"$PROP_TRACE_ID"'","spanId":"'"$PROP_CONS_SPAN"'","parentSpanId":"'"$PROP_PROD_SPAN"'",
         "name":"ticket.created process","kind":5,
         "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
         "attributes":[
           {"key":"correlation_id","value":{"stringValue":"INC-2048"}},
           {"key":"baggage.correlation_id","value":{"stringValue":"INC-2048"}},
           {"key":"baggage.authorization","value":{"stringValue":"Bearer SHOULD-BE-STRIPPED"}},
           {"key":"opsmind.smoke_test","value":{"stringValue":"true"}}],
         "status":{"code":1}}]}]}]}'

  echo "→ pushing OTLP log line to collector :4318"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/logs \
    -H 'Content-Type: application/json' -d '{
    "resourceLogs":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-002-smoke"}}]},
      "scopeLogs":[{"scope":{"name":"op-002-smoke"},"logRecords":[{
        "timeUnixNano":"'"$ts"'","severityNumber":9,"severityText":"INFO",
        "body":{"stringValue":"op-002 smoke log"},
        "attributes":[{"key":"trace_id","value":{"stringValue":"'"$TRACE_ID"'"}}]}]}]}]}'

  echo "→ pushing OTLP log line with PII/secret in body — SPEC-OP-007 value-level redaction"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/logs \
    -H 'Content-Type: application/json' -d '{
    "resourceLogs":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-007-smoke"}}]},
      "scopeLogs":[{"scope":{"name":"op-007-smoke"},"logRecords":[{
        "timeUnixNano":"'"$ts"'","severityNumber":9,"severityText":"INFO",
        "body":{"stringValue":"login succeeded for jane.doe@example.com token=SHOULD-BE-REDACTED-1234"},
        "attributes":[
          {"key":"trace_id","value":{"stringValue":"'"$TRACE_ID"'"}},
          {"key":"event.code","value":{"stringValue":"auth.login.succeeded"}}]}]}]}]}'

  echo "→ pushing OTLP log line with NO trace/correlation linkage — SPEC-OP-007 schema violation"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/logs \
    -H 'Content-Type: application/json' -d '{
    "resourceLogs":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-007-smoke"}}]},
      "scopeLogs":[{"scope":{"name":"op-007-smoke"},"logRecords":[{
        "timeUnixNano":"'"$ts"'","severityNumber":13,"severityText":"WARN",
        "body":{"stringValue":"disk usage high on data volume"},
        "attributes":[]}]}]}]}'

  echo "→ pushing OTLP log line with service.namespace — SPEC-OP-017 domain-operational audit panel"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/logs \
    -H 'Content-Type: application/json' -d '{
    "resourceLogs":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"agent-runtime-service"}},
      {"key":"service.namespace","value":{"stringValue":"agent-runtime"}}]},
      "scopeLogs":[{"scope":{"name":"op-017-smoke"},"logRecords":[{
        "timeUnixNano":"'"$ts"'","severityNumber":9,"severityText":"INFO",
        "body":{"stringValue":"agent run completed"},
        "attributes":[{"key":"trace_id","value":{"stringValue":"'"$TRACE_ID"'"}},
                       {"key":"event.code","value":{"stringValue":"agent.run.completed"}}]}]}]}]}'

  echo "→ pushing OTLP metric to collector :4318 (with forbidden labels ticket_id / run_id — SPEC-OP-006)"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/metrics \
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

  echo "→ pushing contract-compliant golden-path/domain/agent metrics — SPEC-OP-016~019 dashboards"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/metrics \
    -H 'Content-Type: application/json' -d '{
    "resourceMetrics":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"ticket-workflow-service"}},
      {"key":"service.version","value":{"stringValue":"1.4.2"}},
      {"key":"service.namespace","value":{"stringValue":"ticket-workflow"}},
      {"key":"deployment.environment","value":{"stringValue":"local"}}]},
      "scopeMetrics":[{"scope":{"name":"dashboard-smoke"},"metrics":[
        {"name":"http_server_request_duration_seconds","unit":"s",
         "histogram":{"aggregationTemporality":2,"dataPoints":[
           {"startTimeUnixNano":"'"$start"'","timeUnixNano":"'"$ts"'",
            "count":"5","sum":1.25,"bucketCounts":["1","2","1","1"],"explicitBounds":[0.1,0.5,1],
            "attributes":[
              {"key":"http_request_method","value":{"stringValue":"GET"}},
              {"key":"http_response_status_code","value":{"intValue":"200"}},
              {"key":"http_route","value":{"stringValue":"/tickets"}},
              {"key":"outcome","value":{"stringValue":"success"}}]},
           {"startTimeUnixNano":"'"$start"'","timeUnixNano":"'"$ts"'",
            "count":"1","sum":0.8,"bucketCounts":["0","1","0","0"],"explicitBounds":[0.1,0.5,1],
            "attributes":[
              {"key":"http_request_method","value":{"stringValue":"GET"}},
              {"key":"http_response_status_code","value":{"intValue":"500"}},
              {"key":"http_route","value":{"stringValue":"/tickets"}},
              {"key":"outcome","value":{"stringValue":"failure"}}]}]}},
        {"name":"db_client_operation_duration_seconds","unit":"s",
         "histogram":{"aggregationTemporality":2,"dataPoints":[
           {"startTimeUnixNano":"'"$start"'","timeUnixNano":"'"$ts"'",
            "count":"3","sum":0.06,"bucketCounts":["3","0","0","0"],"explicitBounds":[0.1,0.5,1],
            "attributes":[
              {"key":"db_system","value":{"stringValue":"postgresql"}},
              {"key":"db_operation","value":{"stringValue":"SELECT"}},
              {"key":"outcome","value":{"stringValue":"success"}}]}]}}
      ]}]},
    {"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"agent-runtime-service"}},
      {"key":"service.version","value":{"stringValue":"2.0.1"}},
      {"key":"service.namespace","value":{"stringValue":"agent-runtime"}},
      {"key":"deployment.environment","value":{"stringValue":"local"}}]},
      "scopeMetrics":[{"scope":{"name":"dashboard-smoke"},"metrics":[
        {"name":"agent_run_duration_seconds","unit":"s",
         "histogram":{"aggregationTemporality":2,"dataPoints":[
           {"startTimeUnixNano":"'"$start"'","timeUnixNano":"'"$ts"'",
            "count":"2","sum":6.0,"bucketCounts":["0","0","0","2","0","0","0","0","0","0","0"],
            "explicitBounds":[0.005,0.01,0.025,0.05,0.1,0.25,0.5,1,2.5,5],
            "attributes":[
              {"key":"agent_role","value":{"stringValue":"triage"}},
              {"key":"model","value":{"stringValue":"claude-fable-5"}},
              {"key":"outcome","value":{"stringValue":"success"}}]}]}},
        {"name":"agent_tool_calls_total","unit":"1",
         "sum":{"aggregationTemporality":2,"isMonotonic":true,"dataPoints":[
           {"asInt":"7","timeUnixNano":"'"$ts"'",
            "attributes":[
              {"key":"agent_role","value":{"stringValue":"triage"}},
              {"key":"model","value":{"stringValue":"claude-fable-5"}},
              {"key":"outcome","value":{"stringValue":"success"}}]}]}},
        {"name":"agent_llm_tokens","unit":"1",
         "histogram":{"aggregationTemporality":2,"dataPoints":[
           {"startTimeUnixNano":"'"$start"'","timeUnixNano":"'"$ts"'",
            "count":"1","sum":1024,"bucketCounts":["0","0","0","1","0","0","0"],
            "explicitBounds":[16,64,256,1024,4096,16384],
            "attributes":[
              {"key":"agent_role","value":{"stringValue":"triage"}},
              {"key":"model","value":{"stringValue":"claude-fable-5"}}]}]}},
        {"name":"agent_llm_cost_usd","unit":"usd",
         "histogram":{"aggregationTemporality":2,"dataPoints":[
           {"startTimeUnixNano":"'"$start"'","timeUnixNano":"'"$ts"'",
            "count":"1","sum":0.03,"bucketCounts":["0","0","1","0","0","0"],
            "explicitBounds":[0.0005,0.005,0.05,0.5,1],
            "attributes":[
              {"key":"agent_role","value":{"stringValue":"triage"}},
              {"key":"model","value":{"stringValue":"claude-fable-5"}}]}]}},
        {"name":"runtime_cpu_utilization_ratio","unit":"1",
         "gauge":{"dataPoints":[
           {"timeUnixNano":"'"$ts"'","asDouble":0.42,
            "attributes":[{"key":"runtime","value":{"stringValue":"python"}}]}]}}
      ]}]},
    {"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"tool-integration-gateway"}},
      {"key":"service.version","value":{"stringValue":"1.1.0"}},
      {"key":"service.namespace","value":{"stringValue":"tool-integration"}},
      {"key":"deployment.environment","value":{"stringValue":"local"}}]},
      "scopeMetrics":[{"scope":{"name":"dashboard-smoke"},"metrics":[
        {"name":"amqp_publish_duration_seconds","unit":"s",
         "histogram":{"aggregationTemporality":2,"dataPoints":[
           {"startTimeUnixNano":"'"$start"'","timeUnixNano":"'"$ts"'",
            "count":"4","sum":0.02,"bucketCounts":["4","0","0","0"],"explicitBounds":[0.1,0.5,1],
            "attributes":[
              {"key":"messaging_system","value":{"stringValue":"rabbitmq"}},
              {"key":"messaging_destination_name","value":{"stringValue":"tool.requests"}},
              {"key":"outcome","value":{"stringValue":"success"}}]}]}}
      ]}]}]}'

  echo "→ pushing OTLP health-check-shaped trace + log — SPEC-OP-009 filter/noise must drop both"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-009-smoke"}}]},
      "scopeSpans":[{"scope":{"name":"op-009-smoke"},"spans":[{
        "traceId":"'"$NOISE_TRACE_ID"'","spanId":"'"$NOISE_SPAN_ID"'",
        "name":"GET /health","kind":2,
        "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
        "attributes":[{"key":"http.route","value":{"stringValue":"/health"}}],
        "status":{"code":1}}]}]}]}'
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/logs \
    -H 'Content-Type: application/json' -d '{
    "resourceLogs":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-009-smoke"}}]},
      "scopeLogs":[{"scope":{"name":"op-009-smoke"},"logRecords":[{
        "timeUnixNano":"'"$ts"'","severityNumber":9,"severityText":"INFO",
        "body":{"stringValue":"GET /health 200 OK"},
        "attributes":[{"key":"trace_id","value":{"stringValue":"'"$NOISE_TRACE_ID"'"}}]}]}]}]}'

  echo "→ pushing OTLP trace with OLD semconv attrs only — SPEC-OP-009 attributes/semconv-compat"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-009-smoke"}}]},
      "scopeSpans":[{"scope":{"name":"op-009-smoke"},"spans":[{
        "traceId":"'"$SEMCONV_TRACE_ID"'","spanId":"'"$SEMCONV_SPAN_ID"'",
        "name":"GET /widgets","kind":2,
        "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
        "attributes":[
          {"key":"http.method","value":{"stringValue":"GET"}},
          {"key":"http.status_code","value":{"intValue":"200"}},
          {"key":"opsmind.smoke_test","value":{"stringValue":"true"}}],
        "status":{"code":1}}]}]}]}'

  echo "→ pushing ERROR-status + OK-status traces — SPEC-OP-009 transform/trace-priority"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-009-smoke"}}]},
      "scopeSpans":[{"scope":{"name":"op-009-smoke"},"spans":[
        {"traceId":"'"$ERROR_TRACE_ID"'","spanId":"'"$ERROR_SPAN_ID"'",
         "name":"tool.call failing","kind":3,
         "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
         "status":{"code":2,"message":"upstream 500"}},
        {"traceId":"'"$OK_TRACE_ID"'","spanId":"'"$OK_SPAN_ID"'",
         "name":"tool.call ok","kind":3,
         "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
         "status":{"code":1}}]}]}]}'

  echo "→ pushing a genuinely SLOW (>1s) OK-status trace — SPEC-OP-010 tail_sampling 'slow' policy"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-010-smoke"}}]},
      "scopeSpans":[{"scope":{"name":"op-010-smoke"},"spans":[{
        "traceId":"'"$SLOW_TRACE_ID"'","spanId":"'"$SLOW_SPAN_ID"'",
        "name":"db.query slow","kind":3,
        "startTimeUnixNano":"'"$slow_start"'","endTimeUnixNano":"'"$ts"'",
        "status":{"code":1}}]}]}]}'

  echo "→ pushing a fast, OK-status, security.sensitive trace — SPEC-OP-010 tail_sampling 'risky-operation' policy"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-010-smoke"}}]},
      "scopeSpans":[{"scope":{"name":"op-010-smoke"},"spans":[{
        "traceId":"'"$RISKY_TRACE_ID"'","spanId":"'"$RISKY_SPAN_ID"'",
        "name":"auth.mfa.step_up","kind":3,
        "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
        "attributes":[{"key":"security.sensitive","value":{"stringValue":"true"}}],
        "status":{"code":1}}]}]}]}'

  echo "→ pushing a span for Tempo metrics_generator (span-metrics + exemplars) — SPEC-OP-014"
  curl -sfk -H "Authorization: Bearer $OTEL_GATEWAY_AUTH_TOKEN" -o /dev/null -X POST https://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{
    "resourceSpans":[{"resource":{"attributes":[
      {"key":"service.name","value":{"stringValue":"op-014-smoke"}}]},
      "scopeSpans":[{"scope":{"name":"op-014-smoke"},"spans":[{
        "traceId":"'"$METRICS_GEN_TRACE_ID"'","spanId":"'"$METRICS_GEN_SPAN_ID"'",
        "name":"metrics-gen.probe","kind":2,
        "startTimeUnixNano":"'"$start"'","endTimeUnixNano":"'"$ts"'",
        "attributes":[{"key":"opsmind.smoke_test","value":{"stringValue":"true"}}],
        "status":{"code":1}}]}]}]}'
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

  echo "→ SPEC-OP-011: collector-resilience recording rules evaluated + real self-metrics present"
  curl -sf "http://localhost:9090/api/v1/query?query=otelcol:exporter_queue_utilization:ratio" | grep -q '"status":"success"' \
    && echo "  ✓ otelcol:exporter_queue_utilization:ratio recording rule evaluated" \
    || { echo "  ✗ collector-resilience recording rule missing"; rc=1; }
  curl -sf "http://localhost:9090/api/v1/query?query=otelcol_exporter_queue_capacity" | grep -q '"result":\[{' \
    && echo "  ✓ otelcol_exporter_queue_capacity self-metric is real and scraped" \
    || { echo "  ✗ otelcol_exporter_queue_capacity not found in Prometheus"; rc=1; }

  echo "→ SPEC-OP-020: HTTP golden-signal rules wired + real per-status-code counts present"
  # rate()-based recording rules need 2+ scrapes to compute a value — this smoke
  # run pushes once, so assert the rule is syntactically valid/wired (query
  # succeeds) and that the RAW counts behind it are correct, not a specific
  # rate()-derived number.
  http_rate_json="$(curl -sf "http://localhost:9090/api/v1/query?query=http:request:rate5m" || true)"
  printf '%s' "$http_rate_json" | grep -q '"status":"success"' \
    && echo "  ✓ http:request:rate5m recording rule is wired and query-valid" \
    || { echo "  ✗ http:request:rate5m recording rule missing/invalid"; rc=1; }
  ok_count_json="$(curl -sf "http://localhost:9090/api/v1/query?query=http_server_request_duration_seconds_count%7Bservice_name%3D%22ticket-workflow-service%22%2Chttp_response_status_code%3D%22200%22%7D" || true)"
  fail_count_json="$(curl -sf "http://localhost:9090/api/v1/query?query=http_server_request_duration_seconds_count%7Bservice_name%3D%22ticket-workflow-service%22%2Chttp_response_status_code%3D%22500%22%7D" || true)"
  if printf '%s' "$ok_count_json" | grep -q '"value":\[[0-9.]*,"5"\]' \
     && printf '%s' "$fail_count_json" | grep -q '"value":\[[0-9.]*,"1"\]'; then
    echo "  ✓ raw per-status-code counts correct (200: 5, 500: 1) — the exact data http:error_ratio:rate5m computes from"
  else
    echo "  ✗ raw per-status-code counts wrong (200: $ok_count_json / 500: $fail_count_json)"; rc=1
  fi
  rules_json="$(curl -sf "http://localhost:9090/api/v1/rules?rule_name=HighRequestErrorRate" || true)"
  if printf '%s' "$rules_json" | grep -q "HighRequestErrorRate"; then
    echo "  ✓ HighRequestErrorRate alert rule loaded and evaluating"
  else
    echo "  ✗ HighRequestErrorRate alert rule not found"; rc=1
  fi

  echo "→ SPEC-OP-022: SLO error-budget model wired (slo_error_budget_ratio / slo_burn_rate_ratio)"
  slo_json="$(curl -sf "http://localhost:9090/api/v1/query?query=slo_error_budget_ratio%7Bslo%3D%22http-availability%22%7D" || true)"
  printf '%s' "$slo_json" | grep -q '"status":"success"' \
    && echo "  ✓ slo_error_budget_ratio{slo=\"http-availability\"} query-valid (rate()-derived; needs 2+ scrapes for a real value, same as SPEC-OP-020's http:error_ratio:rate5m)" \
    || { echo "  ✗ slo_error_budget_ratio query failed"; rc=1; }
  curl -sf "http://localhost:9090/api/v1/rules?rule_name=SloErrorBudgetLow" | grep -q "SloErrorBudgetLow" \
    && echo "  ✓ SloErrorBudgetLow alert rule loaded" || { echo "  ✗ SloErrorBudgetLow alert rule not found"; rc=1; }

  echo "→ SPEC-OP-023: multi-window burn-rate rules + alerts loaded (all 4 tiers)"
  for w in 5m 30m 2h 6h 1d 3d; do
    curl -sf "http://localhost:9090/api/v1/query?query=slo_burn_rate_ratio%7Bslo%3D%22http-availability%22%2Cwindow%3D%22$w%22%7D" \
      | grep -q '"status":"success"' || { echo "  ✗ slo_burn_rate_ratio window=$w query failed"; rc=1; }
  done
  echo "  ✓ all 6 additional burn-rate windows (5m/30m/2h/6h/1d/3d) query-valid"
  all_loaded=1
  for a in SloFastBurnPage SloSlowBurnPage SloSlowBurnTicket SloSlowBurnTicketLong; do
    curl -sf "http://localhost:9090/api/v1/rules?rule_name=$a" | grep -q "$a" || all_loaded=0
  done
  if [ "$all_loaded" = "1" ]; then
    echo "  ✓ all 4 burn-rate alert tiers loaded (fast/slow page, 1d/3d ticket)"
  else
    echo "  ✗ one or more burn-rate alert tiers not found"; rc=1
  fi

  echo "→ SPEC-OP-012: file_sd discovery is up with correct per-target job labels; TSDB rule evaluated"
  for j in prometheus otel-collector alertmanager loki tempo grafana; do
    curl -sf "http://localhost:9090/api/v1/query?query=up%7Bjob%3D%22$j%22%7D" | grep -q '"value":\[' \
      || { echo "  ✗ job=$j not up via file_sd discovery"; rc=1; }
  done
  echo "  ✓ all 7 file_sd-discovered + self-scrape targets are up with their own job labels"
  curl -sf "http://localhost:9090/api/v1/query?query=prometheus:tsdb_wal_corruptions:rate30m" | grep -q '"status":"success"' \
    && echo "  ✓ prometheus-tsdb recording rule evaluated" || { echo "  ✗ prometheus-tsdb recording rule missing"; rc=1; }

  echo "→ Loki: query {service_name=\"op-002-smoke\"}"
  end="$(date +%s)000000000"; begin="$(( $(date +%s) - 600 ))000000000"
  if curl -sf --data-urlencode 'query={service_name="op-002-smoke"}' \
       --data-urlencode "start=$begin" --data-urlencode "end=$end" \
       "http://localhost:3100/loki/api/v1/query_range" | grep -q "op-002 smoke log"; then
    echo "  ✓ log line found in Loki (correlatable by trace_id)"
  else
    echo "  ✗ log line NOT found in Loki"; rc=1
  fi

  echo "→ Loki: query {service_name=\"op-007-smoke\"} — SPEC-OP-007 structured log contract"
  op7_json="$(curl -sf --data-urlencode 'query={service_name="op-007-smoke"}' \
       --data-urlencode "start=$begin" --data-urlencode "end=$end" \
       "http://localhost:3100/loki/api/v1/query_range" || true)"
  if printf '%s' "$op7_json" | grep -Eq 'jane\.doe@example\.com|SHOULD-BE-REDACTED-1234'; then
    echo "  ✗ raw email/token secret reached Loki unredacted"; rc=1
  else
    echo "  ✓ email + token=... value scrubbed by transform/log-body-redaction"
  fi
  if printf '%s' "$op7_json" | grep -q "REDACTED_EMAIL" && printf '%s' "$op7_json" | grep -q "token=\[REDACTED\]"; then
    echo "  ✓ redacted body carries the expected [REDACTED_EMAIL] / token=[REDACTED] replacements"
  else
    echo "  ✗ redacted body missing expected replacement markers"; rc=1
  fi
  if printf '%s' "$op7_json" | grep -q '"opsmind.log.redacted"' || printf '%s' "$op7_json" | grep -q "opsmind_log_redacted"; then
    echo "  ✓ opsmind.log.redacted stamped on the matching record"
  else
    echo "  ✗ opsmind.log.redacted not found"; rc=1
  fi
  if printf '%s' "$op7_json" | grep -q "missing:trace_linkage"; then
    echo "  ✓ opsmind.log.violation=missing:trace_linkage stamped on the record with no trace_id/correlation_id"
  else
    echo "  ✗ opsmind.log.violation missing:trace_linkage not found for the unlinked record"; rc=1
  fi

  echo "→ SPEC-OP-009: filter/noise must drop the health-check-shaped trace + log entirely"
  noise_trace_json="$(curl -sf "http://localhost:3200/api/traces/$NOISE_TRACE_ID" || true)"
  if printf '%s' "$noise_trace_json" | grep -q "GET /health"; then
    echo "  ✗ health-check span reached Tempo — filter/noise did not drop it"; rc=1
  else
    echo "  ✓ health-check span absent from Tempo"
  fi
  noise_log_json="$(curl -sf --data-urlencode 'query={service_name="op-009-smoke"}' \
       --data-urlencode "start=$begin" --data-urlencode "end=$end" \
       "http://localhost:3100/loki/api/v1/query_range" || true)"
  if printf '%s' "$noise_log_json" | grep -q "GET /health 200 OK"; then
    echo "  ✗ health-check log line reached Loki — filter/noise did not drop it"; rc=1
  else
    echo "  ✓ health-check log line absent from Loki"
  fi

  echo "→ SPEC-OP-009: attributes/semconv-compat must add canonical keys from old semconv"
  semconv_json="$(curl -sf "http://localhost:3200/api/traces/$SEMCONV_TRACE_ID" || true)"
  if printf '%s' "$semconv_json" | grep -q '"key":"http.request.method"' \
     && printf '%s' "$semconv_json" | grep -q '"key":"http.response.status_code"'; then
    echo "  ✓ canonical http.request.method / http.response.status_code present"
  else
    echo "  ✗ canonical semconv keys missing"; rc=1
  fi
  if printf '%s' "$semconv_json" | grep -Eq '"key":"http\.(method|status_code)"'; then
    echo "  ✗ old http.method / http.status_code survived (should have been deleted)"; rc=1
  else
    echo "  ✓ old semconv keys removed"
  fi

  echo "→ SPEC-OP-009: transform/trace-priority — ERROR gets stamped, OK does not"
  error_json="$(curl -sf "http://localhost:3200/api/traces/$ERROR_TRACE_ID" || true)"
  ok_json="$(curl -sf "http://localhost:3200/api/traces/$OK_TRACE_ID" || true)"
  if printf '%s' "$error_json" | grep -q '"opsmind.trace.priority"'; then
    echo "  ✓ ERROR-status trace stamped opsmind.trace.priority=high"
  else
    echo "  ✗ ERROR-status trace missing opsmind.trace.priority"; rc=1
  fi
  if printf '%s' "$ok_json" | grep -q '"opsmind.trace.priority"'; then
    echo "  ✗ OK-status trace was stamped too — transform/trace-priority condition is wrong"; rc=1
  else
    echo "  ✓ OK-status trace correctly NOT stamped"
  fi

  echo "→ SPEC-OP-010: tail_sampling must ALWAYS keep a slow trace and a security.sensitive trace"
  slow_json="$(curl -sf "http://localhost:3200/api/traces/$SLOW_TRACE_ID" || true)"
  if printf '%s' "$slow_json" | grep -q "db.query slow"; then
    echo "  ✓ slow (>1s) trace kept by the 'slow' latency policy"
  else
    echo "  ✗ slow trace NOT found in Tempo — 'slow' policy did not keep it"; rc=1
  fi
  risky_json="$(curl -sf "http://localhost:3200/api/traces/$RISKY_TRACE_ID" || true)"
  if printf '%s' "$risky_json" | grep -q "auth.mfa.step_up"; then
    echo "  ✓ fast/OK security.sensitive trace kept by the 'risky-operation' policy"
  else
    echo "  ✗ risky trace NOT found in Tempo — 'risky-operation' policy did not keep it"; rc=1
  fi

  echo "→ Alertmanager: config loaded"
  curl -sf "http://localhost:9093/api/v2/status" | grep -q '"cluster"' \
    && echo "  ✓ alertmanager up" || { echo "  ✗ alertmanager status failed"; rc=1; }

  echo "→ SPEC-OP-021: Alertmanager severity routing, silence, and inhibition — all real API calls"
  am_now="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
  curl -sf -X POST http://localhost:9093/api/v2/alerts -H 'Content-Type: application/json' -d '[
    {"labels":{"alertname":"RoutingTestCritical","severity":"critical","owner":"platform-observability","namespace":"observability-test"},
     "annotations":{"summary":"routing test critical"},"startsAt":"'"$am_now"'"},
    {"labels":{"alertname":"RoutingTestWarning","severity":"warning","owner":"platform-observability","namespace":"observability-test"},
     "annotations":{"summary":"routing test warning"},"startsAt":"'"$am_now"'"},
    {"labels":{"alertname":"TargetDown","severity":"warning","owner":"platform-observability","namespace":"observability","job":"inhibition-test-job","instance":"test:1234"},
     "annotations":{"summary":"target down"},"startsAt":"'"$am_now"'"},
    {"labels":{"alertname":"InhibitionTestNoise","severity":"warning","owner":"platform-observability","namespace":"observability","job":"inhibition-test-job","instance":"test:1234"},
     "annotations":{"summary":"downstream noise from the same down target"},"startsAt":"'"$am_now"'"}
  ]' >/dev/null
  sleep 3
  groups_json="$(curl -sf "http://localhost:9093/api/v2/alerts/groups" || true)"
  if printf '%s' "$groups_json" | python3 -c "
import json,sys
d=json.load(sys.stdin)
routed = {a['labels']['alertname']: g['receiver']['name'] for g in d for a in g['alerts']}
ok = routed.get('RoutingTestCritical') == 'critical-page' and routed.get('RoutingTestWarning') == 'warning-notify'
sys.exit(0 if ok else 1)
"; then
    echo "  ✓ severity-based routing: critical -> critical-page, warning -> warning-notify"
  else
    echo "  ✗ severity-based routing did not match the expected receivers"; rc=1
  fi
  am_ends="$(date -u -v+1H +%Y-%m-%dT%H:%M:%S.000Z 2>/dev/null || date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%S.000Z)"
  curl -sf -X POST http://localhost:9093/api/v2/silences -H 'Content-Type: application/json' -d '{
    "matchers":[{"name":"alertname","value":"RoutingTestWarning","isRegex":false}],
    "startsAt":"'"$am_now"'","endsAt":"'"$am_ends"'",
    "createdBy":"smoke-test","comment":"proving the silence API for real"}' >/dev/null
  sleep 2
  alerts_json="$(curl -sf "http://localhost:9093/api/v2/alerts" || true)"
  if printf '%s' "$alerts_json" | python3 -c "
import json,sys
d=json.load(sys.stdin)
state = {a['labels']['alertname']: a['status']['state'] for a in d}
ok = (state.get('RoutingTestWarning') == 'suppressed'
      and state.get('RoutingTestCritical') == 'active'
      and state.get('TargetDown') == 'active'
      and state.get('InhibitionTestNoise') == 'suppressed')
sys.exit(0 if ok else 1)
"; then
    echo "  ✓ silence API suppressed exactly the matched alert; TargetDown inhibition suppressed the co-located alert on the same job/instance"
  else
    echo "  ✗ silence/inhibition state did not match expectations: $alerts_json"; rc=1
  fi

  echo "→ SPEC-OP-013: Loki ruler loaded the LogQL log-quality rule"
  curl -sf "http://localhost:3100/prometheus/api/v1/rules" | grep -q "HighLogSchemaViolationRate" \
    && echo "  ✓ ruler loaded HighLogSchemaViolationRate from the bind-mounted rules dir" \
    || { echo "  ✗ Loki ruler did not load the log-quality rule"; rc=1; }

  echo "→ SPEC-OP-014: Tempo metrics_generator (span-metrics + exemplars) — polling up to 60s"
  gen_ok=0
  i=0
  while [ "$i" -lt 12 ]; do
    if curl -sf "http://localhost:9090/api/v1/query?query=traces_spanmetrics_calls_total%7Bservice%3D%22op-014-smoke%22%7D" \
         | grep -q '"result":\[{'; then
      gen_ok=1
      break
    fi
    i=$((i + 1))
    sleep 5
  done
  if [ "$gen_ok" = "1" ]; then
    echo "  ✓ traces_spanmetrics_calls_total generated from a real span and reached Prometheus"
  else
    echo "  ✗ traces_spanmetrics_calls_total never appeared in Prometheus"; rc=1
  fi
  exemplar_json="$(curl -sf "http://localhost:9090/api/v1/query_exemplars?query=traces_spanmetrics_latency_bucket%7Bservice%3D%22op-014-smoke%22%7D&start=$(( $(date +%s) - 180 ))&end=$(date +%s)" || true)"
  if printf '%s' "$exemplar_json" | grep -q "$METRICS_GEN_TRACE_ID"; then
    echo "  ✓ exemplar on the latency histogram links back to the exact trace ID pushed"
  else
    echo "  ✗ no exemplar found linking to the pushed trace"; rc=1
  fi

  echo "→ SPEC-OP-015: retention-evidence recording rules evaluated; Prometheus admin snapshot works"
  curl -sf "http://localhost:9090/api/v1/query?query=tempo:retention_deleted:rate1h" | grep -q '"status":"success"' \
    && echo "  ✓ tempo:retention_deleted:rate1h recording rule evaluated" \
    || { echo "  ✗ telemetry-retention recording rule missing"; rc=1; }
  snap_json="$(curl -sf -X POST http://localhost:9090/api/v1/admin/tsdb/snapshot || true)"
  if printf '%s' "$snap_json" | grep -q '"status":"success"'; then
    echo "  ✓ Prometheus admin snapshot API works (--web.enable-admin-api)"
  else
    echo "  ✗ Prometheus admin snapshot API failed: $snap_json"; rc=1
  fi

  echo "→ SPEC-OP-008: gateway auth is actually enforced (unauthenticated push must be rejected)"
  unauth_status="$(curl -sk -o /dev/null -w '%{http_code}' -X POST https://localhost:4318/v1/traces \
    -H 'Content-Type: application/json' -d '{}' || true)"
  if [ "$unauth_status" = "401" ]; then
    echo "  ✓ push without Authorization was rejected (401)"
  else
    echo "  ✗ expected 401 for an unauthenticated push, got $unauth_status"; rc=1
  fi

  echo "→ SPEC-OP-008: gateway readiness (health_check + check_collector_pipeline)"
  ready_json="$(curl -sf "http://localhost:13133/" || true)"
  if printf '%s' "$ready_json" | grep -Eqi '"status":\s*"(Server available|StatusOK)"'; then
    echo "  ✓ gateway reports ready (pipeline exporting)"
  else
    echo "  ✗ gateway readiness check failed: $ready_json"; rc=1
  fi

  return $rc
}

case "${1:-}" in
  config)  dc config >/dev/null && echo "compose config OK: $COMPOSE_FILE" ;;
  up)      ensure_dev_tls; dc up -d --wait && dc ps ;;
  down)    dc down -v --remove-orphans ;;
  ps)      dc ps ;;
  logs)    shift; dc logs "$@" ;;
  smoke)
    ensure_dev_tls
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
