"""SPEC-ARO-033 11-security §"Data Protection": "payload 中 PII 必须最小化" / "logs 中禁止
输出 secret、token、完整工具响应." 12-observability §"日志": "日志不输出 secret、token、完整
PII payload." No already-built-but-unwired signal named this spec anywhere in the
codebase (unlike SPEC-ARO-032's three explicit pointers) — its concrete scope came from
auditing every view that currently echoes a raw, caller-supplied payload string back out
of the process. Two exist: CheckpointView.payload (SPEC-ARO-006's query API, any consumer)
and PoisonEventView.payload (SPEC-ARO-024's admin visibility surface — the more likely
real target, since a poisoned delivery is by definition unparsed/unvalidated content that
could carry anything). Grepping every logger.info/warning/error/debug call in this
codebase found none that already embeds a raw payload string (tests/architecture/
test_log_payload_redaction.py now guards that staying true), so log output needed no
separate redaction path — only these two outward-facing views did.

redact_payload() is a best-effort, known-key-name redaction, not a general PII scrubber
or a claim of exhaustively catching every possible secret shape — the same "honest about
what it does and doesn't do" stance StaticCapabilityPolicyAdapter/
StaticWorkflowDefinitionCatalogAdapter already take for their own placeholder scope. A
plain regex over the raw string (not a json.loads()-then-walk approach) is deliberate:
PoisonEventRecord.payload is by definition not guaranteed to be valid JSON (that is
what made the delivery poisoned in the first place), so any redaction must degrade
gracefully on malformed input rather than only working on the well-formed
CheckpointRecord.payload case.

Redaction is applied only in the view-construction layer (CheckpointView/
PoisonEventView.from_record()), never to the underlying CheckpointRecord/
PoisonEventRecord themselves — the persisted artifact stays intact for Runtime's own
internal use (e.g. a future poison-event replay needs the real payload, not a redacted
one) and CheckpointRecord.checksum keeps meaning "checksum of the payload actually
recorded," not of whatever a view later chooses to redact. A consequence worth naming
explicitly: sha256(CheckpointView.payload) no longer equals CheckpointView.checksum once
a payload is actually redacted — no current consumer in this codebase relies on that
equality (checksum is only ever asserted truthy in tests, never recomputed against the
exposed payload), so this is a deliberate, low-risk tradeoff, not an oversight.
"""

from __future__ import annotations

import re

# Case-insensitive; matches the JSON-string-value shape `"<key>": "<value>"` for any of
# these key names (with optional camelCase/snake_case/kebab-case variants for the
# multi-word ones), mirroring 11-security's own "secret、token" wording plus the most
# common concrete forms those take in tool/API payloads.
_SENSITIVE_KEY_PATTERN = re.compile(
    r'(?i)("(?:password|secret|token|api[_-]?key|access[_-]?token|refresh[_-]?token|'
    r'client[_-]?secret|authorization|credentials?)"\s*:\s*")([^"\\]*)(")'
)

_REDACTED_VALUE = "***REDACTED***"


def redact_payload(payload: str) -> str:
    """Masks the string value of any JSON key matching a known-sensitive name
    (password/secret/token/apiKey/accessToken/refreshToken/clientSecret/
    authorization/credential(s)), leaving every other key and the surrounding
    structure untouched. Degrades gracefully on non-JSON or malformed input — it is a
    plain regex substitution over the raw string, not a JSON parse, so a poisoned
    delivery's own truncated/invalid payload is still scanned rather than skipped.
    """
    return _SENSITIVE_KEY_PATTERN.sub(rf"\1{_REDACTED_VALUE}\3", payload)
