from __future__ import annotations

from datetime import UTC, datetime

import pytest

from evaluationimprovement.domain.evaluation_run import EvaluationRun
from evaluationimprovement.domain.ids import DatasetId, RunId
from evaluationimprovement.domain.state_machine import InvalidStateTransitionException
from evaluationimprovement.domain.values import VersionBinding

_NOW = datetime.now(UTC)


def _run() -> EvaluationRun:
    binding = VersionBinding(
        dataset_version="2026.08.1", target_version="agent-runtime:rc1", grader_bundle_version="v1",
        policy_version="v1", correlation_id="corr-1", baseline_version="agent-runtime:2026.08.20",
    )
    return EvaluationRun.create(RunId.new_id(), "ci-main-001", DatasetId.new_id(), binding, "ci", _NOW)


@pytest.mark.unit
def test_happy_path_transitions_to_passed() -> None:
    run = _run().start().enter_scoring().enter_comparing().pass_(_NOW)
    assert run.status.value == "PASSED"
    assert run.is_final is True


@pytest.mark.unit
def test_scoring_can_fail_directly() -> None:
    run = _run().start().enter_scoring().fail(_NOW)
    assert run.status.value == "FAILED"


@pytest.mark.unit
def test_running_can_go_partial() -> None:
    run = _run().start().mark_partial(_NOW)
    assert run.status.value == "PARTIAL"
    assert run.is_final is True


@pytest.mark.unit
def test_queued_can_be_cancelled() -> None:
    run = _run().cancel(_NOW)
    assert run.status.value == "CANCELLED"


@pytest.mark.unit
def test_passed_run_is_terminal() -> None:
    run = _run().start().enter_scoring().enter_comparing().pass_(_NOW)
    with pytest.raises(InvalidStateTransitionException):
        run.cancel(_NOW)


@pytest.mark.unit
def test_scoring_cannot_skip_directly_to_passed() -> None:
    run = _run().start().enter_scoring()
    with pytest.raises(InvalidStateTransitionException):
        run.pass_(_NOW)
