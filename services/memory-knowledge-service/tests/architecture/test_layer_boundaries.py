"""Runs the import-linter contracts declared in pyproject.toml ([tool.importlinter])
as a pytest test — the Python-idiomatic equivalent of the Java sibling services'
ArchUnit LayerDependencyTest. Covers: domain must not depend on FastAPI/Pydantic/
SQLAlchemy; application must not depend on infrastructure; interfaces -> application
-> domain layering. Mirrors agent-runtime-service's own
tests/architecture/test_layer_boundaries.py exactly.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from importlinter import configuration
from importlinter.application import use_cases

_PYPROJECT_PATH = Path(__file__).resolve().parents[2] / "pyproject.toml"


@pytest.mark.unit
def test_import_linter_contracts_are_kept() -> None:
    configuration.configure()
    passed = use_cases.lint_imports(config_filename=str(_PYPROJECT_PATH), is_debug_mode=False, show_timings=False)
    assert passed, "import-linter contracts are broken; run `uv run lint-imports` for details"
