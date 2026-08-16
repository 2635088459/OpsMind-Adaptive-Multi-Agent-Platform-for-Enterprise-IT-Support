"""02-business-invariants §"安全不变量": "PII、secret、access token、完整用户标识不能进入
active memory content." SPEC-MK-001 domain-rules: "敏感数据必须脱敏或拒绝." Regex-based —
real, deterministic, and testable, not a stand-in for a trained PII model (which is a
later phase's real upgrade path, same posture as infrastructure.embedding's own
placeholder).
"""

from __future__ import annotations

import re

from memoryknowledge.domain.values import RedactionReport

_MASK = "***REDACTED***"

_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("email", re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")),
    ("credit_card", re.compile(r"\b(?:\d[ -]?){13,16}\b")),
    ("ssn", re.compile(r"\b\d{3}-\d{2}-\d{4}\b")),
    ("bearer_token", re.compile(r"\bBearer\s+[A-Za-z0-9\-_.]+", re.IGNORECASE)),
    ("key_value_secret", re.compile(
        r"(?i)(\"?(?:password|passwd|secret|token|api[_-]?key|access[_-]?key)\"?\s*[:=]\s*)\"?[^\s,\"'}]+\"?"
    )),
    ("long_hex_or_base64", re.compile(r"\b[A-Za-z0-9+/_=]{32,}\b")),
)


class RegexRedactionPolicyAdapter:
    def redact(self, text: str) -> tuple[str, RedactionReport]:
        redacted = text
        matched_patterns: list[str] = []

        for name, pattern in _PATTERNS:
            if name == "key_value_secret":
                new_redacted, count = pattern.subn(lambda m: f"{m.group(1)}{_MASK}", redacted)
            else:
                new_redacted, count = pattern.subn(_MASK, redacted)
            if count:
                matched_patterns.append(name)
                redacted = new_redacted

        report = RedactionReport(
            redacted_fields=("content",) if matched_patterns else (),
            secret_patterns_matched=tuple(matched_patterns),
            policy_rule_ids=tuple(f"redaction.{name}" for name in matched_patterns),
        )
        return redacted, report
