"""13-package-and-class-design §"Interfaces": "Controllers do not contain business
rules." Only evaluationimprovement.container (the composition root) may import
evaluationimprovement.infrastructure directly; every interfaces module must reach
concrete adapters only through an application.ports_in Protocol handed to it by
container.get_*_port(). Mirrors memory-knowledge-service's own
tests/architecture/test_interfaces_boundary.py exactly.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

_SRC_ROOT = Path(__file__).resolve().parents[2] / "src" / "evaluationimprovement"
_INTERFACES_ROOT = _SRC_ROOT / "interfaces"


def _imports_infrastructure(path: Path) -> bool:
    tree = ast.parse(path.read_text(), filename=str(path))
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("evaluationimprovement.infrastructure"):
            return True
        if isinstance(node, ast.Import) and any(alias.name.startswith("evaluationimprovement.infrastructure") for alias in node.names):
            return True
    return False


@pytest.mark.unit
def test_interfaces_never_import_infrastructure_directly() -> None:
    offenders = [path for path in _INTERFACES_ROOT.rglob("*.py") if _imports_infrastructure(path)]
    assert not offenders, f"evaluationimprovement.interfaces must reach adapters only via evaluationimprovement.container, but found direct imports in: {offenders}"
