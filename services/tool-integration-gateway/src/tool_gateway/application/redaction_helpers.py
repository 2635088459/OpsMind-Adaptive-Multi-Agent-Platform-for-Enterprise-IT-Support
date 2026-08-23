"""SPEC-TG-014 11-security §"Output Redaction": "Every connector result must
pass classification/redaction ... Only redacted output may enter event
payloads or Memory Knowledge." A connector's ``structured_output`` is
untrusted free-form JSON — a secret-shaped string can appear inside any nested
value, not only in the top-level ``summary`` string. Applying
``RedactionPort.redact()`` only to ``summary`` (SPEC-TG-001's own original
scope) left every other field of a connector's output completely unredacted
before it reached a published event or API response; this module closes that
gap by walking the whole structure.
"""

from __future__ import annotations

from typing import Any

from tool_gateway.ports.redaction_port import RedactionPort


def redact_structured_output(redaction_port: RedactionPort, data: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    """Recursively redacts every string leaf value in ``data`` (dicts and
    lists are walked, other JSON-safe scalar types pass through unchanged).
    Returns ``(redacted_data, any_field_redacted)``.
    """

    any_redacted = False

    def _walk(value: Any) -> Any:
        nonlocal any_redacted
        if isinstance(value, str):
            redacted, metadata = redaction_port.redact(value)
            if metadata.redacted_fields:
                any_redacted = True
            return redacted
        if isinstance(value, dict):
            return {key: _walk(item) for key, item in value.items()}
        if isinstance(value, list):
            return [_walk(item) for item in value]
        return value

    return _walk(data), any_redacted
