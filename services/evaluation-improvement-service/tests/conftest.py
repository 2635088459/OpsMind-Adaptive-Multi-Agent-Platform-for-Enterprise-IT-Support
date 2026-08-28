"""Shared fixture: a real evaluationimprovement.container.Container built purely from
SPEC-EI-001's own in-memory adapters, mirroring memory-knowledge-service's own
tests/application convention of exercising real application services against real (if
in-memory) collaborators rather than mocks. Settings.evaluation_persistence defaults
to "postgres" (SPEC-EI-002) for production honesty, so every hermetic unit/
application/end-to-end test under this directory must never attempt a real database
connection. The env var is set process-wide (before Settings.get_settings()'s own
lru_cache is ever populated) so this covers both the `container` fixture below *and*
tests/test_app.py's own create_app() -> get_container() -> Settings() path, which
does not go through this fixture at all. Real-Postgres coverage lives in
tests/integration (testcontainers), which points EVALUATION_PERSISTENCE back to
"postgres" for its own ephemeral container.
"""

from __future__ import annotations

import os

os.environ.setdefault("EVALUATION_PERSISTENCE", "memory")

import pytest  # noqa: E402

from evaluationimprovement.container import Container  # noqa: E402


@pytest.fixture()
def container() -> Container:
    return Container()
