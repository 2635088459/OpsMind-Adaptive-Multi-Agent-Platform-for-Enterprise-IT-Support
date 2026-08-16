"""13-package-and-class-design §"Interfaces": "Controllers do not contain business
rules." Only memoryknowledge.container (the composition root) may import
memoryknowledge.infrastructure directly; every interfaces module must reach concrete
adapters only through a memoryknowledge.application.ports_in Protocol handed to it by
container.get_*_port(). Checked as a direct-import scan (see test_layer_boundaries.py's
docstring for why this isn't an import-linter "forbidden" contract). Mirrors
agent-runtime-service's own tests/architecture/test_interfaces_boundary.py exactly.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

_SRC_ROOT = Path(__file__).resolve().parents[2] / "src" / "memoryknowledge"
_INTERFACES_ROOT = _SRC_ROOT / "interfaces"


def _imports_infrastructure(path: Path) -> bool:
    tree = ast.parse(path.read_text(), filename=str(path))
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("memoryknowledge.infrastructure"):
            return True
        if isinstance(node, ast.Import) and any(alias.name.startswith("memoryknowledge.infrastructure") for alias in node.names):
            return True
    return False


@pytest.mark.unit
def test_interfaces_never_import_infrastructure_directly() -> None:
    offenders = [path for path in _INTERFACES_ROOT.rglob("*.py") if _imports_infrastructure(path)]
    assert not offenders, f"memoryknowledge.interfaces must reach adapters only via memoryknowledge.container, but found direct imports in: {offenders}"
