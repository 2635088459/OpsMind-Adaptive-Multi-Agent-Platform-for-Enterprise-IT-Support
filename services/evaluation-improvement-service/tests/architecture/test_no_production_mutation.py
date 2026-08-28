"""SPEC-EI-001 domain-rules / traceability-entry.yaml `forbidden` list:
direct_production_agent_prompt_mutation, direct_ticket_state_write,
direct_workflow_state_write, direct_tool_execution, direct_memory_content_write,
policy_approval_ownership_bypass. 02-business-invariants INV-EI-001: "07 不能直接修改
生产 Agent、Prompt、Policy、Tool Connector、Ticket、Workflow 或 Memory state."

This is checked structurally: no function/method defined anywhere in this service's
own source tree is named after a mutating verb against one of those foreign
aggregates. A genuine violation would need a new method whose very name admits what
it does (`create_ticket`, `complete_workflow`, `execute_tool`, `grant_approval`, ...);
this test fails the moment one is added, rather than relying on a reviewer to notice.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

import pytest

_SRC_ROOT = Path(__file__).resolve().parents[2] / "src" / "evaluationimprovement"

_FORBIDDEN_NAME_PATTERNS = (
    re.compile(r"^(create|update|delete|close|resolve|reopen|write)_ticket"),
    re.compile(r"^(create|update|complete|cancel|mutate|write)_workflow"),
    re.compile(r"^(execute|run)_tool$"),
    re.compile(r"^(write|publish|delete)_memory"),
    re.compile(r"^(grant|deny)_approval$"),
    re.compile(r"^approve_policy$"),
    re.compile(r"^(mutate|write|update)_(agent|prompt)_config"),
)


def _defined_function_names(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(), filename=str(path))
    return [node.name for node in ast.walk(tree) if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))]


@pytest.mark.unit
def test_no_function_mutates_a_foreign_aggregate_by_name() -> None:
    offenders: list[str] = []
    for path in _SRC_ROOT.rglob("*.py"):
        for name in _defined_function_names(path):
            if any(pattern.match(name) for pattern in _FORBIDDEN_NAME_PATTERNS):
                offenders.append(f"{path.relative_to(_SRC_ROOT)}::{name}")
    assert not offenders, (
        "found a function whose name admits it directly mutates a foreign domain's "
        f"aggregate (Ticket/Workflow/Tool/Memory/Policy/Agent/Prompt), forbidden by "
        f"02-business-invariants INV-EI-001: {offenders}"
    )


@pytest.mark.unit
def test_agent_runtime_port_never_declares_a_mutating_method() -> None:
    """AgentRuntimeEvaluationPort's only method is execute_case() — a read/simulate
    call against mock system state (13-package-and-class-design), never a write back
    to the real Agent Runtime workflow the way a production tool-execution call would.
    """
    from evaluationimprovement.application import ports_out

    method_names = {name for name in dir(ports_out.AgentRuntimeEvaluationPort) if not name.startswith("_")}
    assert method_names == {"execute_case"}


@pytest.mark.unit
def test_policy_approval_port_never_grants_or_denies() -> None:
    """domain-rules "forbidden: policy_approval_ownership_bypass" — this port may only
    ever *request* an approval; granting/denying is exclusively 06's own decision.
    """
    from evaluationimprovement.application import ports_out

    method_names = {name for name in dir(ports_out.PolicyApprovalPort) if not name.startswith("_")}
    assert method_names == {"request_approval"}
