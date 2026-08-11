"""13-package-and-class-design §"Interfaces": "Controllers do not contain business
rules." Only agentruntime.container (the composition root) may import
agentruntime.infrastructure directly; every interfaces module must reach concrete
adapters only through a agentruntime.application.ports_in Protocol handed to it by
container.get_*_port(). Checked as a direct-import scan (see
test_layer_boundaries.py's docstring for why this isn't an import-linter "forbidden"
contract: that type follows the whole transitive graph, which would also flag the
legitimate interfaces -> container -> infrastructure composition-root edge).
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

_SRC_ROOT = Path(__file__).resolve().parents[2] / "src" / "agentruntime"
_INTERFACES_ROOT = _SRC_ROOT / "interfaces"


def _imports_infrastructure(path: Path) -> bool:
    tree = ast.parse(path.read_text(), filename=str(path))
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("agentruntime.infrastructure"):
            return True
        if isinstance(node, ast.Import) and any(alias.name.startswith("agentruntime.infrastructure") for alias in node.names):
            return True
    return False


@pytest.mark.unit
def test_interfaces_never_import_infrastructure_directly() -> None:
    offenders = [path for path in _INTERFACES_ROOT.rglob("*.py") if _imports_infrastructure(path)]
    assert not offenders, f"agentruntime.interfaces must reach adapters only via agentruntime.container, but found direct imports in: {offenders}"
