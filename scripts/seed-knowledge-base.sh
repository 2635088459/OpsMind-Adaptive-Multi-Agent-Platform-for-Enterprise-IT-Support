#!/usr/bin/env bash
#
# Seed the shared IT-support RAG knowledge base (04-memory-knowledge-service).
#
# Loads every markdown document listed in
#   services/memory-knowledge-service/seed/manifest.json
# through the real ingestion pipeline (POST /internal/memory/v1/admin/documents,
# which runs parse -> chunk -> embed -> index -> ACTIVE synchronously), then runs
# a handful of sample retrieval queries against POST /internal/memory/v1/search to
# prove the corpus is actually retrievable by the employee conversational path
# (agent-runtime-service calls that endpoint with requester_type=EMPLOYEE,
# role=EMPLOYEE, classification=INTERNAL).
#
# (source_system, external_id, version) is the ingestion idempotency natural key,
# so re-running this script is a no-op for unchanged documents and a conflict
# (HTTP 409) only if a file's content changed under the same version -- bump the
# "version" in manifest.json to re-ingest changed content.
#
# Usage:
#   scripts/seed-knowledge-base.sh
#   MK_BASE_URL=http://localhost:8010 scripts/seed-knowledge-base.sh
#
# Env:
#   MK_BASE_URL   Base URL of memory-knowledge-service (default http://localhost:8010)

set -euo pipefail

MK_BASE_URL="${MK_BASE_URL:-http://localhost:8010}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="${SCRIPT_DIR}/../services/memory-knowledge-service/seed"
MANIFEST="${SEED_DIR}/manifest.json"

command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
[ -f "$MANIFEST" ] || { echo "manifest not found: $MANIFEST" >&2; exit 1; }

echo "memory-knowledge-service : $MK_BASE_URL"
echo "seed corpus             : $SEED_DIR"
echo

# ---------------------------------------------------------------------------
# 1. Ingest every document in the manifest.
# ---------------------------------------------------------------------------
ingested=0
skipped_unchanged=0
failed=0

# manifest.json -> TSV: file \t external_id \t title \t document_type \t version
while IFS=$'\t' read -r file external_id title document_type version; do
  [ -n "$file" ] || continue
  src_file="${SEED_DIR}/${file}"
  if [ ! -f "$src_file" ]; then
    echo "  MISSING  ${file} (listed in manifest, not on disk)"
    failed=$((failed + 1))
    continue
  fi

  payload="$(
    MK_FILE="$src_file" MK_EXTID="$external_id" MK_TITLE="$title" MK_DOCTYPE="$document_type" MK_VERSION="$version" \
    MK_MANIFEST="$MANIFEST" python3 - <<'PY'
import json, os
manifest = json.load(open(os.environ["MK_MANIFEST"]))
raw = open(os.environ["MK_FILE"], encoding="utf-8").read()
print(json.dumps({
    "source_system": manifest["source_system"],
    "external_id": os.environ["MK_EXTID"],
    "title": os.environ["MK_TITLE"],
    "document_type": os.environ["MK_DOCTYPE"],
    "version": int(os.environ["MK_VERSION"]),
    "raw_content": raw,
    "ingested_by": manifest["ingested_by"],
    "classification": "INTERNAL",
    "acl": [],
}))
PY
  )"

  resp="$(curl -sS -o /tmp/mk_seed_body.$$ -w '%{http_code}' \
    -X POST "${MK_BASE_URL}/internal/memory/v1/admin/documents" \
    -H 'Content-Type: application/json' -d "$payload" || true)"
  body="$(cat /tmp/mk_seed_body.$$ 2>/dev/null || true)"
  rm -f /tmp/mk_seed_body.$$

  case "$resp" in
    201)
      read -r status chunks <<<"$(printf '%s' "$body" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("ingestion_status",""), d.get("chunk_count",0))')"
      echo "  OK       ${external_id}  (${status}, ${chunks} chunks)"
      ingested=$((ingested + 1))
      ;;
    200)
      echo "  UNCHANGED ${external_id}  (identical content already ingested)"
      skipped_unchanged=$((skipped_unchanged + 1))
      ;;
    409)
      echo "  CONFLICT ${external_id}  (content changed under version ${version} -- add a per-doc \"version\": $((version + 1)) to its manifest.json entry)"
      failed=$((failed + 1))
      ;;
    *)
      echo "  FAIL     ${external_id}  (HTTP ${resp}) ${body:0:200}"
      failed=$((failed + 1))
      ;;
  esac
done < <(python3 -c '
import json, sys
m = json.load(open(sys.argv[1]))
default_version = m["version"]
for d in m["documents"]:
    version = str(d.get("version", default_version))
    print("\t".join([d["file"], d["external_id"], d["title"], d["document_type"], version]))
' "$MANIFEST")

echo
echo "ingested=${ingested} unchanged=${skipped_unchanged} failed=${failed}"

# ---------------------------------------------------------------------------
# 2. Verify retrieval: each probe must come back with at least one result.
# ---------------------------------------------------------------------------
echo
echo "retrieval probes (requester_type=EMPLOYEE, classification=INTERNAL):"

PROBES=(
  "my vpn will not connect"
  "how do I reset my password"
  "outlook keeps asking for my password"
  "my mailbox is full and I cannot send email"
  "how do I submit a maintenance request in the housing portal"
  "my laptop will not turn on"
  "external monitor is not detected"
  "how do I report a phishing email"
  "what are the support priority levels and response times"
  "can I paste confidential data into an AI tool"
)

probe_empty=0
for q in "${PROBES[@]}"; do
  search_payload="$(MK_Q="$q" python3 - <<'PY'
import json, os, uuid
print(json.dumps({
    "query": os.environ["MK_Q"],
    "requester_type": "EMPLOYEE",
    "requester_id": "seed-verify",
    "access_scope": {"tenant": "default", "role": "EMPLOYEE", "classification": "INTERNAL"},
    "correlation_id": str(uuid.uuid4()),
    "filters": {"max_results": 3, "include_graph_paths": False},
}))
PY
  )"
  body="$(curl -sS -X POST "${MK_BASE_URL}/internal/memory/v1/search" \
    -H 'Content-Type: application/json' -d "$search_payload" || true)"
  line="$(printf '%s' "$body" | python3 -c '
import sys, json
d = json.load(sys.stdin)
r = d.get("results", [])
if not r:
    print("EMPTY\t")
else:
    top = r[0]
    snip = " ".join(top["snippet"].split())
    count = len(r)
    score = float(top["score"])
    print("%d hit(s), top score %.3f\t%s" % (count, score, snip[:110]))
' 2>/dev/null || printf 'PARSE-ERROR\t%s' "${body:0:120}")"
  count="${line%%$'\t'*}"
  detail="${line#*$'\t'}"
  printf '  [%s]\n    q: %s\n    -> %s\n' "$count" "$q" "$detail"
  [ "$count" = "EMPTY" ] && probe_empty=$((probe_empty + 1))
done

echo
if [ "$failed" -ne 0 ] || [ "$probe_empty" -ne 0 ]; then
  echo "RESULT: FAIL (ingest failures=${failed}, empty probes=${probe_empty})"
  exit 1
fi
echo "RESULT: OK -- corpus ingested and retrievable"
