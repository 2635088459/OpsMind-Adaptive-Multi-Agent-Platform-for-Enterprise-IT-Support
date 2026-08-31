"""SPEC-EI-012/SPEC-EI-013: Settings-driven adapter selection in container.py — the
seam every hermetic test elsewhere in this suite relies on staying at its safe
default ("fake"/"noop") unless a test explicitly opts in, mirrored here directly
against a purpose-built Container(settings=...) rather than the shared `container`
fixture (which always uses in-memory persistence but does not otherwise override
these two modes).
"""

from __future__ import annotations

import pytest

from evaluationimprovement.container import Container
from evaluationimprovement.infrastructure.runtime.agent_runtime_client import (
    FakeAgentRuntimeEvaluationAdapter,
    HttpAgentRuntimeEvaluationAdapter,
)
from evaluationimprovement.settings import Settings


@pytest.mark.unit
def test_default_settings_wire_the_fake_agent_runtime_adapter_and_noop_langsmith() -> None:
    container = Container(settings=Settings(evaluation_persistence="memory"))
    assert isinstance(container.agent_runtime_port, FakeAgentRuntimeEvaluationAdapter)
    assert container.langsmith_port.is_enabled() is False


@pytest.mark.unit
def test_http_mode_wires_the_real_agent_runtime_client() -> None:
    container = Container(settings=Settings(evaluation_persistence="memory", agent_runtime_evaluation_mode="http"))
    assert isinstance(container.agent_runtime_port, HttpAgentRuntimeEvaluationAdapter)
