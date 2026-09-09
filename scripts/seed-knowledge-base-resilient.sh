#!/usr/bin/env bash
#
# Wrapper around scripts/seed-knowledge-base.sh for the EMBEDDING_PROVIDER=openai
# case on a flaky link. Each chunk embed is already retried with backoff inside
# OpenAIEmbeddingProvider, but if a document's whole retry budget is exhausted it
# lands FAILED, and seed-knowledge-base.sh's (source_system, external_id, version)
# idempotency then *skips* it on a re-run. This script drops the FAILED
# it-knowledge-base rows and re-runs the seed, looping until every document is
# ACTIVE (or MAX_PASSES is hit).
#
# Only useful with EMBEDDING_PROVIDER=openai; with the deterministic embedder the
# base script never leaves a FAILED row and one pass is enough.
#
# Usage:  scripts/seed-knowledge-base-resilient.sh
# Env:    MK_BASE_URL (default http://localhost:8010)
#         PG_CONTAINER (default opsmind-postgres)  PG_USER/PG_DB (default ticket_workflow)
#         MAX_PASSES (default 8)   PASS_SLEEP_SECONDS (default 5)

set -euo pipefail

MK_BASE_URL="${MK_BASE_URL:-http://localhost:8010}"
PG_CONTAINER="${PG_CONTAINER:-opsmind-postgres}"
PG_USER="${PG_USER:-ticket_workflow}"
PG_DB="${PG_DB:-ticket_workflow}"
MAX_PASSES="${MAX_PASSES:-8}"
PASS_SLEEP_SECONDS="${PASS_SLEEP_SECONDS:-5}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

psql() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1"; }
count_by_status() { psql "select status||'='||count(*) from memory.knowledge_documents where source_system='it-knowledge-base' group by status" | paste -sd' ' -; }

drop_failed() {
  psql "
    WITH d AS (SELECT id FROM memory.knowledge_documents WHERE source_system='it-knowledge-base' AND status='FAILED')
    DELETE FROM memory.outbox_events WHERE aggregate_id IN (SELECT id::text FROM d);
  " >/dev/null
  psql "DELETE FROM memory.audit_events WHERE resource_id IN (SELECT id::text FROM memory.knowledge_documents WHERE source_system='it-knowledge-base' AND status='FAILED')" >/dev/null
  psql "DELETE FROM memory.knowledge_documents WHERE source_system='it-knowledge-base' AND status='FAILED'" >/dev/null
}

TOTAL="$(grep -c '"external_id"' "${SCRIPT_DIR}/../services/memory-knowledge-service/seed/manifest.json" || echo 0)"
echo "target: $TOTAL documents ACTIVE  (max $MAX_PASSES passes)"

for pass in $(seq 1 "$MAX_PASSES"); do
  echo
  echo "=== pass $pass/$MAX_PASSES ==="
  drop_failed
  # base script exits non-zero if any probe is empty / any ingest hard-fails;
  # a FAILED-status doc still returns 201 there, so tolerate a non-zero exit and
  # decide from the DB instead.
  bash "${SCRIPT_DIR}/seed-knowledge-base.sh" 2>&1 | grep -E '  (OK|FAIL|UNCHANGED)|ingested=' || true

  active="$(psql "select count(*) from memory.knowledge_documents where source_system='it-knowledge-base' and status='ACTIVE'")"
  echo "status: $(count_by_status)   (ACTIVE $active / $TOTAL)"
  if [ "$active" -ge "$TOTAL" ]; then
    echo
    echo "RESULT: OK — all $TOTAL documents ACTIVE after $pass pass(es)"
    exit 0
  fi
  sleep "$PASS_SLEEP_SECONDS"
done

echo
echo "RESULT: INCOMPLETE — still short after $MAX_PASSES passes: $(count_by_status)"
exit 1
