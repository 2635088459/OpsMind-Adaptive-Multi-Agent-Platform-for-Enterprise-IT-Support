"""13-package-and-class-design §"Dependency Direction": "api calls application
services" / "workers call application services and do not manipulate database
directly." Only tool_gateway.container (the composition root) may import
tool_gateway.adapters directly; every tool_gateway.api/tool_gateway.workers
module must reach concrete adapters only through a
tool_gateway.application.ports_in Protocol handed to it by container.get_*_port().
Checked as a direct-import scan (see test_layer_boundaries.py's docstring for
why this isn't an import-linter "forbidden" contract). Mirrors
memory-knowledge-service's own tests/architecture/test_interfaces_boundary.py
exactly, extended to cover both api/ and workers/ (this domain's own
13-package-and-class-design keeps workers as a sibling package, not nested
under application/infrastructure as in memory-knowledge-service).
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

_SRC_ROOT = Path(__file__).resolve().parents[2] / "src" / "tool_gateway"
_CHECKED_ROOTS = (_SRC_ROOT / "api", _SRC_ROOT / "workers")


def _imports_adapters(path: Path) -> bool:
    tree = ast.parse(path.read_text(), filename=str(path))
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("tool_gateway.adapters"):
            return True
        if isinstance(node, ast.Import) and any(alias.name.startswith("tool_gateway.adapters") for alias in node.names):
            return True
    return False


@pytest.mark.unit
def test_api_and_workers_never_import_adapters_directly() -> None:
    offenders = [path for root in _CHECKED_ROOTS for path in root.rglob("*.py") if _imports_adapters(path)]
    assert not offenders, f"tool_gateway.api/tool_gateway.workers must reach adapters only via tool_gateway.container, but found direct imports in: {offenders}"
