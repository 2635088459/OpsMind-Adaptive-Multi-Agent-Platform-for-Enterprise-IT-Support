"""01-domain-model §"EvaluationRun". 03-state-machine §"EvaluationRun"."""

from __future__ import annotations

import dataclasses
from datetime import datetime

from evaluationimprovement.domain.enums import RunStatus
from evaluationimprovement.domain.ids import DatasetId, RunId
from evaluationimprovement.domain.state_machine import StateMachine
from evaluationimprovement.domain.values import VersionBinding

_TRANSITIONS: dict[RunStatus, frozenset[RunStatus]] = {
    RunStatus.QUEUED: frozenset({RunStatus.RUNNING, RunStatus.CANCELLED}),
    RunStatus.RUNNING: frozenset({RunStatus.SCORING, RunStatus.PARTIAL, RunStatus.FAILED, RunStatus.CANCELLED}),
    # SPEC-EI-009: 03-state-machine's own diagram only names RUNNING -> PARTIAL, but
    # whether a case is scoreable is only known once its own CaseExecutionResult
    # exists — which, for a FAILED/SKIPPED case, is only discovered at
    # finalize_scoring() time (status already SCORING by then; score_case() itself
    # drives the RUNNING -> SCORING transition on the first case). IncompleteRunException's
    # own pre-existing docstring already named "or be explicitly marked
    # skipped/failed before a run can leave SCORING" — SCORING -> PARTIAL is that
    # promise's only reachable path, not an invented one.
    RunStatus.SCORING: frozenset({RunStatus.COMPARING, RunStatus.FAILED, RunStatus.PARTIAL}),
    RunStatus.COMPARING: frozenset({RunStatus.PASSED, RunStatus.FAILED}),
    RunStatus.PASSED: frozenset(),
    RunStatus.FAILED: frozenset(),
    RunStatus.CANCELLED: frozenset(),
    RunStatus.PARTIAL: frozenset(),
}
_STATE_MACHINE: StateMachine[RunStatus] = StateMachine("EvaluationRun", _TRANSITIONS)


@dataclasses.dataclass(frozen=True, slots=True)
class EvaluationRun:
    """01-domain-model lists `runtimeVersion`/`memoryVersion`/`policyVersion`/
    `toolGatewayVersion` alongside `datasetVersion`/`targetVersion`/`baselineVersion`;
    those four collapse into `version_binding.policy_version` /
    `version_binding.grader_bundle_version` here — 02-business-invariants INV-EI-006
    only actually requires dataset/target/grader/policy/hash/correlation to be bound,
    and VersionBinding is the one place that binding is enforced non-blank, rather than
    duplicating four more optional string fields on this aggregate.
    """

    run_id: RunId
    run_key: str
    dataset_id: DatasetId
    version_binding: VersionBinding
    status: RunStatus
    triggered_by: str
    started_at: datetime
    completed_at: datetime | None = None

    @staticmethod
    def create(
        run_id: RunId, run_key: str, dataset_id: DatasetId, version_binding: VersionBinding, triggered_by: str, now: datetime,
    ) -> "EvaluationRun":
        if not run_key or not run_key.strip():
            raise ValueError("runKey must not be blank")
        if not triggered_by or not triggered_by.strip():
            raise ValueError("triggeredBy must not be blank")
        return EvaluationRun(
            run_id=run_id, run_key=run_key, dataset_id=dataset_id, version_binding=version_binding,
            status=RunStatus.QUEUED, triggered_by=triggered_by, started_at=now,
        )

    def start(self) -> "EvaluationRun":
        _STATE_MACHINE.assert_transition(self.status, RunStatus.RUNNING)
        return dataclasses.replace(self, status=RunStatus.RUNNING)

    def enter_scoring(self) -> "EvaluationRun":
        _STATE_MACHINE.assert_transition(self.status, RunStatus.SCORING)
        return dataclasses.replace(self, status=RunStatus.SCORING)

    def enter_comparing(self) -> "EvaluationRun":
        _STATE_MACHINE.assert_transition(self.status, RunStatus.COMPARING)
        return dataclasses.replace(self, status=RunStatus.COMPARING)

    def pass_(self, now: datetime) -> "EvaluationRun":
        _STATE_MACHINE.assert_transition(self.status, RunStatus.PASSED)
        return dataclasses.replace(self, status=RunStatus.PASSED, completed_at=now)

    def fail(self, now: datetime) -> "EvaluationRun":
        _STATE_MACHINE.assert_transition(self.status, RunStatus.FAILED)
        return dataclasses.replace(self, status=RunStatus.FAILED, completed_at=now)

    def mark_partial(self, now: datetime) -> "EvaluationRun":
        _STATE_MACHINE.assert_transition(self.status, RunStatus.PARTIAL)
        return dataclasses.replace(self, status=RunStatus.PARTIAL, completed_at=now)

    def cancel(self, now: datetime) -> "EvaluationRun":
        _STATE_MACHINE.assert_transition(self.status, RunStatus.CANCELLED)
        return dataclasses.replace(self, status=RunStatus.CANCELLED, completed_at=now)

    @property
    def is_final(self) -> bool:
        return _STATE_MACHINE.is_terminal(self.status)
