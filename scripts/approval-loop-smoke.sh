#!/usr/bin/env bash
# OpsMind event-relay smoke (SPEC-XREL-001 cross-service integration check).
#
# Proves the ONE thing the relay's own unit suite cannot: a message published to
# the real `opsmind.events` exchange is consumed by the running event-relay
# sidecar, transformed, POSTed to the real agent-runtime HTTP event seam, and
# settled (ack / dlq) correctly against real downstream responses.
#
# It does NOT drive the full HIGH-risk business loop (conversation -> high-risk
# proposal -> governance approval request -> 3-user SoD approval -> resume): that
# path depends on the reasoning adapter actually emitting a high-risk proposal
# and on governance's separation-of-duties orchestration, which
# scripts/seed-governance-policies.sh already exercises. Here we inject the two
# governance-shaped envelopes directly and assert the relay's behaviour.
#
# Requires the full platform up (event-relay included):
#   docker compose -f infrastructure/docker-compose/local-platform.yml \
#                  -f infrastructure/docker-compose/full-platform.yml up -d
#   scripts/approval-loop-smoke.sh
set -euo pipefail

RELAY_CTR="${RELAY_CONTAINER:-opsmind-event-relay}"
AR="${AGENT_RUNTIME_URL:-http://localhost:8000}"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m %s\n' "$*"; }
die() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

say "0. the event-relay sidecar is up"
STATUS="$(docker inspect -f '{{.State.Status}}' "$RELAY_CTR" 2>/dev/null || echo missing)"
[ "$STATUS" = "running" ] || die "container $RELAY_CTR is '$STATUS' (is the full platform up?)"
ok "$RELAY_CTR is running"

# Publish a shared-envelope message onto opsmind.events from inside the relay
# container (it has pika + network reach to rabbitmq). $1 = routing key, $2 = JSON
# body, $3 = optional W3C traceparent to stamp as an AMQP header (so we can assert
# the relay continues it through to the downstream HTTP POST).
publish() {
  docker exec -i "$RELAY_CTR" python - "$1" "$2" "${3:-}" <<'PY'
import os, sys, pika
routing_key, body, traceparent = sys.argv[1], sys.argv[2], sys.argv[3]
params = pika.ConnectionParameters(
    host=os.environ.get("RABBITMQ_HOST", "rabbitmq"),
    port=int(os.environ.get("RABBITMQ_PORT", "5672")),
    virtual_host=os.environ.get("RABBITMQ_VHOST", "/"),
    credentials=pika.PlainCredentials(
        os.environ.get("RABBITMQ_USERNAME", "guest"), os.environ.get("RABBITMQ_PASSWORD", "guest")
    ),
)
conn = pika.BlockingConnection(params)
ch = conn.channel()
ch.exchange_declare(exchange="opsmind.events", exchange_type="topic", durable=True)
headers = {"traceparent": traceparent} if traceparent else None
ch.basic_publish(exchange="opsmind.events", routing_key=routing_key, body=body.encode(),
                 properties=pika.BasicProperties(content_type="application/json", delivery_mode=2, headers=headers))
conn.close()
print("published", routing_key, "traceparent=" + (traceparent or "(none)"))
PY
}

# A fresh W3C trace id (32 hex) for this run; a fixed parent span id is fine.
TRACE_ID="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
TRACEPARENT="00-${TRACE_ID}-b7ad6b7169203331-01"

relay_logs_since() { docker logs --since "$1" "$RELAY_CTR" 2>&1; }

EVT1="xrel-smoke-$(date +%s)-1"
say "1. publish approval.granted.v1 (with a W3C traceparent) -> relay POSTs, agent-runtime 404s, relay ACKs"
echo "   traceparent trace-id: $TRACE_ID"
T0="$(date -u +%FT%TZ)"
publish "approval.granted.v1" "$(cat <<JSON
{"eventId":"$EVT1","eventType":"approval.granted.v1","producer":"policy-approval-governance-service",
 "schemaVersion":1,"aggregateId":"ar-smoke-1","ticketId":"$(uuidgen)","correlationId":"sched-smoke-1",
 "occurredAt":"$T0","payload":{"approvalRequestId":"ar-smoke-1","sourceDomain":"agent-runtime",
 "workflowInstanceId":"$(uuidgen)","toolRequestId":null,"decidedBy":"ops.smoke","reason":"smoke"}}
JSON
)" "$TRACEPARENT"
sleep 6
LOGS="$(relay_logs_since "$T0")"
echo "$LOGS" | grep -q "action=relay_consumed .*event_id=$EVT1" || { echo "$LOGS" | tail -20; die "relay never logged consuming $EVT1"; }
echo "$LOGS" | grep -q "action=relay_delivery .*event_id=$EVT1 .*target=agent-runtime .*status=404 .*outcome=ack" \
  || { echo "$LOGS" | grep "$EVT1" || true; die "expected agent-runtime 404 -> outcome=ack for $EVT1"; }
echo "$LOGS" | grep -q "action=relay_settled .*event_id=$EVT1 .*outcome=ack" || die "expected the message to settle as ack"
ok "approval.granted.v1 transformed + POSTed; 404 correctly classified as ack (no poison loop)"

# The relay must have CONTINUED the inbound traceparent, not rooted a new trace:
# the consume line and the downstream-delivery line both carry the same trace-id.
echo "$LOGS" | grep -q "action=relay_consumed .*event_id=$EVT1 .*trace_id=$TRACE_ID" \
  || { echo "$LOGS" | grep "$EVT1" | grep relay_consumed || true; die "relay did not continue the inbound traceparent on consume ($TRACE_ID)"; }
echo "$LOGS" | grep -q "action=relay_delivery .*event_id=$EVT1 .*trace_id=$TRACE_ID .*target=agent-runtime" \
  || { echo "$LOGS" | grep "$EVT1" | grep relay_delivery || true; die "the downstream POST did not carry the inbound trace-id ($TRACE_ID)"; }
ok "trace context propagated: AMQP traceparent -> relay consume span -> downstream HTTP POST, all trace_id=$TRACE_ID"

EVT2="xrel-smoke-$(date +%s)-2"
say "2. publish improvement.promoted.v1 -> relay forwards the raw envelope to /events/improvement-promoted"
T1="$(date -u +%FT%TZ)"
publish "improvement.promoted.v1" "$(cat <<JSON
{"eventId":"$EVT2","eventType":"improvement.promoted.v1","producer":"evaluation-improvement-service",
 "schemaVersion":1,"aggregateId":"cand-smoke","correlationId":"$(uuidgen)","occurredAt":"$T1",
 "payload":{"candidate_id":"cand-smoke","candidate_type":"PROMPT","target_component":"triage-prompt",
 "promoted_version":"v-smoke","proposed_change":{"text":"smoke"}}}
JSON
)"
sleep 6
LOGS="$(relay_logs_since "$T1")"
echo "$LOGS" | grep -q "action=relay_delivery .*event_id=$EVT2 .*path=/internal/agent-runtime/v1/events/improvement-promoted" \
  || { echo "$LOGS" | grep "$EVT2" || true; die "relay did not POST $EVT2 to the improvement-promoted seam"; }
echo "$LOGS" | grep -q "action=relay_settled .*event_id=$EVT2 .*outcome=\(ack\|dlq\)" \
  || die "improvement.promoted message never settled"
ok "improvement.promoted.v1 forwarded to the agent-runtime seam"

say "3. nothing was left unacked / hot-looping"
# The relay's own queue should be back to 0 ready messages (both settled).
READY="$(docker exec opsmind-rabbitmq rabbitmqctl list_queues name messages_ready 2>/dev/null \
  | awk '$1=="event-relay.python-fanout.v1"{print $2}')"
echo "     event-relay.python-fanout.v1 messages_ready=${READY:-?}"
[ "${READY:-0}" = "0" ] || die "relay queue still has ${READY} ready message(s) — a message is stuck"
ok "relay queue drained"

printf '\n\033[1;32mPASS\033[0m — opsmind.events -> event-relay -> agent-runtime HTTP seam: transform + settle + W3C trace propagation verified.\n'
