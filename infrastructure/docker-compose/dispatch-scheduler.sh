#!/bin/sh
# The "external scheduler" the platform's outbox/recovery javadocs and docstrings
# keep referring to:
#
#   "draining is ... safe to call from an admin endpoint or an external scheduler
#    once one exists"  (policy-approval-governance OutboxDispatchService, and
#    mirrored in ticket-workflow / memory-knowledge / agent-runtime / tool-gateway)
#
# Every service deliberately has NO in-process @Scheduled / background drain loop.
# This container is the one that exists: it periodically POSTs each service's
# admin dispatch/recovery endpoint over the compose network. No service code
# changes; this honours the documented seam rather than contradicting it.
#
# Cadence: outbox dispatch every DISPATCH_INTERVAL_SECONDS (default 10s);
# the heavier recovery scans every RECOVERY_EVERY_N cycles (default 6 -> ~60s).
set -u

KC="${KC_URL:-http://keycloak:8080}"
INTERVAL="${DISPATCH_INTERVAL_SECONDS:-10}"
RECOVERY_EVERY_N="${RECOVERY_EVERY_N:-6}"
STARTUP_DELAY="${STARTUP_DELAY_SECONDS:-20}"
ACTOR="dispatch-scheduler"

log() { echo "[scheduler $(date -u +%H:%M:%S)] $*"; }

log "starting: interval=${INTERVAL}s, recovery every ${RECOVERY_EVERY_N} cycles, startup delay ${STARTUP_DELAY}s"
sleep "$STARTUP_DELAY"

get_token() {
  curl -sS --max-time 10 -X POST "$KC/realms/opsmind/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=employee-test-client \
    -d username=test.agent -d password=test-password 2>/dev/null \
  | grep -o '"access_token":"[^"]*"' | sed 's/.*:"//; s/"$//'
}

hit() {  # $1=label ; rest = curl args
  label="$1"; shift
  code="$(curl -sS -o /tmp/resp -w '%{http_code}' --max-time 20 "$@" 2>/dev/null || echo ERR)"
  body="$(tr -d '\n' < /tmp/resp 2>/dev/null | cut -c1-140)"
  log "$label -> $code $body"
}

n=0
while true; do
  n=$((n + 1))
  TOKEN="$(get_token)"
  [ -z "$TOKEN" ] && log "WARN token fetch failed; skipping the two Java endpoints this cycle"

  # --- outbox drain: every cycle ------------------------------------------------
  [ -n "$TOKEN" ] && hit "ticket-workflow  outbox" \
    -X POST "http://ticket-workflow-service:8080/internal/v1/outbox:dispatch" \
    -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: sched-$n"
  [ -n "$TOKEN" ] && hit "policy-governance outbox" \
    -X POST "http://policy-approval-governance-service:8086/api/v1/admin/outbox:dispatch" \
    -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: sched-$n"
  hit "agent-runtime    outbox" \
    -X POST "http://agent-runtime-service:8000/internal/agent-runtime/v1/admin/outbox/dispatch" \
    -H "X-Actor-Id: $ACTOR"
  hit "agent-runtime    tool-requests" \
    -X POST "http://agent-runtime-service:8000/internal/agent-runtime/v1/admin/tool-requests/dispatch" \
    -H "X-Actor-Id: $ACTOR"
  hit "memory-knowledge outbox" \
    -X POST "http://memory-knowledge-service:8010/internal/memory/v1/admin/outbox/dispatch" \
    -H "X-Actor-Id: $ACTOR"
  # evaluation-improvement's POST /evaluation/outbox/dispatch is deliberately left
  # out: it requires an authenticated admin (role manage_gate_policy) and its own
  # docstring frames it as "audited (unlike a background worker's own unattended
  # dispatch loop would be)" — i.e. that one service intends its outbox replay to
  # stay a human/ops action, not a scheduled poke. Its outbox only carries the
  # SPEC-EI-035 langsmith-grader failure-recovery path.

  # --- recovery scans: every RECOVERY_EVERY_N cycles --------------------------
  if [ $((n % RECOVERY_EVERY_N)) -eq 0 ]; then
    [ -n "$TOKEN" ] && hit "policy-governance recovery" \
      -X POST "http://policy-approval-governance-service:8086/api/v1/admin/recovery:run" \
      -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: sched-rec-$n"
    hit "agent-runtime    workflow-recovery" \
      -X POST "http://agent-runtime-service:8000/internal/agent-runtime/v1/admin/workflows/recovery-scan" \
      -H "X-Actor-Id: $ACTOR"
    hit "agent-runtime    lease-recovery" \
      -X POST "http://agent-runtime-service:8000/internal/agent-runtime/v1/admin/agent-tasks/lease-recovery-scan" \
      -H "X-Actor-Id: $ACTOR"
    hit "agent-runtime    tool-wait-recovery" \
      -X POST "http://agent-runtime-service:8000/internal/agent-runtime/v1/admin/agent-tasks/tool-wait-recovery-scan" \
      -H "X-Actor-Id: $ACTOR"
    # SPEC-XREL-001: bounds a WAITING_FOR_APPROVAL workflow whose approval.granted/
    # denied/expired decision never arrived (approval abandoned, governance expiry
    # sweep never ran, or the event-relay was down for the whole window). The relay
    # is the fast path; this is the safety net.
    hit "agent-runtime    approval-wait-recovery" \
      -X POST "http://agent-runtime-service:8000/internal/agent-runtime/v1/admin/workflows/approval-wait-recovery-scan" \
      -H "X-Actor-Id: $ACTOR"
    hit "memory-knowledge recovery/ingestion" \
      -X POST "http://memory-knowledge-service:8010/internal/memory/v1/admin/recovery/ingestion" \
      -H "X-Actor-Id: $ACTOR"
    hit "memory-knowledge recovery/publish-graph" \
      -X POST "http://memory-knowledge-service:8010/internal/memory/v1/admin/recovery/publish-graph" \
      -H "X-Actor-Id: $ACTOR"
    hit "memory-knowledge recovery/retention" \
      -X POST "http://memory-knowledge-service:8010/internal/memory/v1/admin/recovery/retention" \
      -H "X-Actor-Id: $ACTOR"
    hit "tool-gateway     recovery/run" \
      -X POST "http://tool-integration-gateway:8020/internal/tool-gateway/v1/admin/recovery/run"
  fi

  sleep "$INTERVAL"
done
