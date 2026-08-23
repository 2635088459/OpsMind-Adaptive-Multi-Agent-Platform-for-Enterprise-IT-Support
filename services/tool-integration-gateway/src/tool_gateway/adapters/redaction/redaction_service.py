"""13-package-and-class-design §"adapters/redaction/redaction_service.py".
INV-TG-004/INV-TG-007: secrets/raw output must not enter events/logs/memory.
11-security §"Output Redaction": "Every connector result must pass
classification/redaction: secret/token/key, PII, customer data, infrastructure
internal address, privileged diagnostic output." Simple regex-based scrubbing
for the pattern-matchable subset of that list (secret-shaped strings, email
addresses as a PII proxy, RFC 1918 private IPv4 addresses as an infrastructure-
internal-address proxy, PEM private-key blocks) — "customer data" and
"privileged diagnostic output" have no generic pattern and are NOT covered
here; this stays an honestly-labeled placeholder, mirroring memory-knowledge-
service's own ``RegexRedactionPolicyAdapter``. A real DLP/classification
pipeline is a future hardening spec's job (phase-05, SPEC-TG-020~021 "Security
And Credential Boundary").
"""

from __future__ import annotations

import re
from datetime import UTC, datetime

from tool_gateway.domain.values import RedactionMetadata

_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("bearer_token", re.compile(r"(?i)bearer\s+[A-Za-z0-9\-_.=]+")),
    ("api_key_pair", re.compile(r"(?i)(api[_-]?key|secret|token|password)\s*[:=]\s*\S+")),
    ("aws_access_key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("private_key_block", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.DOTALL)),
    ("email_address", re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")),
    # RFC 1918 private IPv4 ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) —
    # 11-security's own "infrastructure internal address" example.
    ("internal_ipv4", re.compile(r"\b(?:10(?:\.\d{1,3}){3}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}|192\.168(?:\.\d{1,3}){2})\b")),
)


class RegexRedactionAdapter:
    def redact(self, raw_text: str) -> tuple[str, RedactionMetadata]:
        redacted_text = raw_text
        redacted_fields: list[str] = []
        for name, pattern in _PATTERNS:
            if pattern.search(redacted_text):
                redacted_fields.append(name)
                redacted_text = pattern.sub("[REDACTED]", redacted_text)
        status = "REDACTED" if redacted_fields else "NOT_REQUIRED"
        return redacted_text, RedactionMetadata(status=status, redacted_fields=tuple(redacted_fields), applied_at=datetime.now(UTC))
