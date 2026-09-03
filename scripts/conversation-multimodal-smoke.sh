#!/usr/bin/env sh
# OpsMind conversational-intake + multimodal smoke test (SPEC-ARO-039's own follow-up).
#
# Drives the real, already-running local platform end-to-end with plain curl — no
# frontend app exists yet for domain 09-employee-portal, so this is the way to
# exercise "employee sends a chat message with a photo attached" locally today:
#
#   scripts/conversation-multimodal-smoke.sh [path/to/image.png]
#
# Defaults to a hand-generated 64x64 red-square/blue-border PNG if no path is given
# (no external image needed). Requires the full platform already up:
#
#   docker compose -f infrastructure/docker-compose/local-platform.yml \
#                  -f infrastructure/docker-compose/full-platform.yml up -d
#
# What this actually proves, in order:
#   1. a real employee JWT from Keycloak (test.agent via employee-test-client —
#      see opsmind-realm.json's own comment for why this client exists: no other
#      client in this realm yields a plain EMPLOYEE-defaulted actor_type token)
#   2. POST /api/v1/conversations — a real ticket-workflow-service ticket gets created
#   3. POST /api/v1/attachments — a real MinIO object + Postgres row
#   4. POST .../messages with attachment_refs — agent-runtime-service really fetches
#      those bytes back from attachment-service and (if CONVERSATION_REASONING_MODE=
#      openai/anthropic in infrastructure/docker-compose/.env) really hands them to
#      the LLM as vision content. Under the default "static" mode this still proves
#      steps 1-4 end-to-end, just without a real model call at the end.
#
# Local URLs this hits directly:
#   Keycloak            http://localhost:8081  (admin console: /admin, realm: opsmind)
#   agent-runtime-service http://localhost:8000 (health: /health)
#   attachment-service   http://localhost:8090  (health: /actuator/health)
#   MinIO console        http://localhost:9001  (opsmind-minio / opsmind-minio-secret by default)
set -eu

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
AGENT_RUNTIME_URL="${AGENT_RUNTIME_URL:-http://localhost:8000}"
ATTACHMENT_URL="${ATTACHMENT_URL:-http://localhost:8090}"
EMPLOYEE_USERNAME="${EMPLOYEE_USERNAME:-test.agent}"
EMPLOYEE_PASSWORD="${EMPLOYEE_PASSWORD:-test-password}"
IMAGE_PATH="${1:-}"
RUN_ID="$(date +%s)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [ -z "$IMAGE_PATH" ]; then
  IMAGE_PATH="$TMP_DIR/red-square-blue-border.png"
  python3 -c "
import struct, zlib

def chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))

width, height = 64, 64
raw = b''
for y in range(height):
    row = b'\x00'
    for x in range(width):
        if x < 4 or x >= width - 4 or y < 4 or y >= height - 4:
            row += bytes([0, 0, 255])
        else:
            row += bytes([255, 0, 0])
    raw += row
compressed = zlib.compress(raw)
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', compressed) + chunk(b'IEND', b'')
open('$IMAGE_PATH', 'wb').write(png)
"
  echo "no image given — generated a test image at $IMAGE_PATH (red square, blue border)"
fi

echo "--- 1. real employee token (Keycloak) ---"
TOKEN_RESPONSE="$TMP_DIR/token.json"
curl -sf -X POST "$KEYCLOAK_URL/realms/opsmind/protocol/openid-connect/token" \
  -d "grant_type=password" -d "client_id=employee-test-client" \
  -d "username=$EMPLOYEE_USERNAME" -d "password=$EMPLOYEE_PASSWORD" \
  -o "$TOKEN_RESPONSE"
TOKEN="$(python3 -c "import json; print(json.load(open('$TOKEN_RESPONSE'))['access_token'])")"
echo "acquired a real JWT for $EMPLOYEE_USERNAME"

echo "--- 2. start a real conversation (real ticket created behind it) ---"
START_RESPONSE="$TMP_DIR/start.json"
curl -sf -X POST "$AGENT_RUNTIME_URL/api/v1/conversations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: smoke-conv-$RUN_ID" \
  -o "$START_RESPONSE"
cat "$START_RESPONSE"
echo
CONVERSATION_ID="$(python3 -c "import json; print(json.load(open('$START_RESPONSE'))['conversation_id'])")"

echo "--- 3. upload the real image (attachment-service) ---"
UPLOAD_RESPONSE="$TMP_DIR/upload.json"
curl -sf -X POST "$ATTACHMENT_URL/api/v1/attachments" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@$IMAGE_PATH;type=image/png" \
  -o "$UPLOAD_RESPONSE"
cat "$UPLOAD_RESPONSE"
echo
ATTACHMENT_REF="$(python3 -c "import json; print(json.load(open('$UPLOAD_RESPONSE'))['ref'])")"

echo "--- 4. send the message with the real attachment ref ---"
curl -sf --max-time 45 -X POST "$AGENT_RUNTIME_URL/api/v1/conversations/$CONVERSATION_ID/messages" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: smoke-msg-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d "{\"text\": \"What colors do you see in this image?\", \"attachment_refs\": [\"$ATTACHMENT_REF\"]}"
echo
