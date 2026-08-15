"""SPEC-ARO-033 11-security §"Data Protection"/12-observability §"日志": "日志不输出
secret、token、完整 PII payload." No log line in this codebase currently embeds a raw
payload string (confirmed by this test at the time it was written); this is a plain
AST scan — mirroring test_tool_gateway_boundary.py's own approach for a different
invariant — that guards against a future logger.info/warning/error/debug call
regressing that by formatting a `payload`-shaped attribute directly (e.g.
`record.payload`, `saved.result_payload`) instead of going through a redacted view or
an id/metadata-only field. Not a general secrets-in-logs scanner — narrowly checks
only for attribute access whose own name contains "payload", the concrete shape every
payload-carrying field in this codebase already uses (CheckpointRecord.payload,
ToolRequestRecord.request_payload/result_payload, PoisonEventRecord.payload,
AgentTaskRecord.result_payload, OutboxRecord.payload).
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

_SRC_ROOT = Path(__file__).resolve().parents[2] / "src" / "agentruntime"
_LOG_METHODS = {"info", "warning", "error", "debug", "exception", "critical"}


def _logger_calls_referencing_a_payload_attribute(path: Path) -> list[int]:
    tree = ast.parse(path.read_text(), filename=str(path))
    offending_lines: list[int] = []
    for node in ast.walk(tree):
        if not (
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and node.func.attr in _LOG_METHODS
            and isinstance(node.func.value, ast.Name)
            and node.func.value.id == "logger"
        ):
            continue
        for arg in list(node.args) + [kw.value for kw in node.keywords]:
            for sub in ast.walk(arg):
                if isinstance(sub, ast.Attribute) and "payload" in sub.attr.lower():
                    offending_lines.append(node.lineno)
    return offending_lines


@pytest.mark.unit
def test_no_logger_call_references_a_raw_payload_attribute() -> None:
    offenders = {
        str(path.relative_to(_SRC_ROOT)): lines
        for path in _SRC_ROOT.rglob("*.py")
        if (lines := _logger_calls_referencing_a_payload_attribute(path))
    }

    assert not offenders, (
        f"logger calls must never format a raw payload attribute directly "
        f"(11-security §\"Data Protection\"): {offenders}"
    )
