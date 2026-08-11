"""02-business-invariants §"Tool Gateway Boundary": "Agents cannot call Tools
directly ... Runtime must centralize authorization, audit, rate limiting, and
retry." Only agentruntime.application.services.request_tool (the one application
service allowed to reach ToolGatewayPort) and agentruntime.application.ports_out
(where it is defined) may reference the name ToolGatewayPort. Mirrors the Java
sibling services' ArchUnit rule
`onlyRequestToolServiceAndItsAdapterMayDependOnToolGatewayPort`, expressed here as a
plain AST import scan since import-linter's contract types cannot single out one
specific name within a package.

08-transaction-and-outbox §"Outbox Publisher" gives EventPublisherPort the exact same
shape of boundary: only DispatchOutboxEventsService may depend on it.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

_SRC_ROOT = Path(__file__).resolve().parents[2] / "src" / "agentruntime"


def _imports_name(path: Path, name: str) -> bool:
    tree = ast.parse(path.read_text(), filename=str(path))
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and any(alias.name == name for alias in node.names):
            return True
    return False


def _offenders(name: str, allowed_files: set[Path]) -> list[Path]:
    return [path for path in _SRC_ROOT.rglob("*.py") if path not in allowed_files and _imports_name(path, name)]


@pytest.mark.unit
def test_only_request_tool_service_and_ports_out_reference_tool_gateway_port() -> None:
    allowed = {_SRC_ROOT / "application" / "ports_out.py", _SRC_ROOT / "application" / "services" / "request_tool.py"}

    offenders = _offenders("ToolGatewayPort", allowed)

    assert not offenders, f"only {allowed} may import ToolGatewayPort, but found it in: {offenders}"


@pytest.mark.unit
def test_only_dispatch_outbox_events_service_and_ports_out_reference_event_publisher_port() -> None:
    allowed = {
        _SRC_ROOT / "application" / "ports_out.py",
        _SRC_ROOT / "application" / "services" / "dispatch_outbox_events.py",
    }

    offenders = _offenders("EventPublisherPort", allowed)

    assert not offenders, f"only {allowed} may import EventPublisherPort, but found it in: {offenders}"
