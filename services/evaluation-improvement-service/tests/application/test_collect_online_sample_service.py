"""SPEC-EI-028 (online-sample-evaluation): CollectOnlineSampleService — collect into
the queue, delayed scoring via score_pending(), and the find_by_candidate() read side
SPEC-EI-029's own EvaluateCanaryPromotionService aggregates over.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import CollectOnlineSampleCommand
from evaluationimprovement.application.records import GraderResult
from evaluationimprovement.application.services.collect_online_sample import CollectOnlineSampleService
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import EvaluationDimension, GraderType, OnlineSampleStatus, ScoreFailureCode
from evaluationimprovement.domain.ids import CandidateId


class _FakeOnlineSampleJudge:
    """A duck-typed grader — same `.grade(sample)` shape
    infrastructure.graders.llm_judge's own PlaceholderOnlineSampleJudge/
    AnthropicOnlineSampleJudge expose, mirroring test_evaluate_judge_calibration_service.py's
    own `_FakeJudge` precedent.
    """

    def __init__(self, scores: list[float]) -> None:
        self._scores = scores
        self.call_count = 0

    def grade(self, sample) -> GraderResult:  # noqa: ANN001, ARG002
        score = self._scores[self.call_count]
        self.call_count += 1
        return GraderResult(
            dimension=EvaluationDimension.HANDOFF_COMPLETENESS, score=score, threshold=0.7, grader_type=GraderType.LLM_JUDGE,
            grader_version="fake-online-sample-judge-v1",
        )


def _command(candidate_id: CandidateId | None = None) -> CollectOnlineSampleCommand:
    return CollectOnlineSampleCommand(
        candidate_id=candidate_id, target_version="agent-runtime:rc1", source_event_type="WORKFLOW_COMPLETED",
        source_trace_ref="trace-redacted-1", redacted_context={"summary": "resolved"}, actor="system",
        correlation_id="corr-1",
    )


@pytest.mark.unit
def test_collect_queues_a_sample(container: Container) -> None:
    view = container.collect_online_sample_service.collect(_command())
    assert view.status == OnlineSampleStatus.QUEUED
    assert view.scored_at is None
    assert view.composite_score is None


@pytest.mark.unit
def test_score_pending_with_the_default_placeholder_judge_marks_samples_unscored(container: Container) -> None:
    container.collect_online_sample_service.collect(_command())
    report = container.collect_online_sample_service.score_pending(batch_size=10)
    assert report.scored == 1
    assert report.mean_composite_score is None


@pytest.mark.unit
def test_score_pending_records_real_scores_and_a_mean(container: Container) -> None:
    judge = _FakeOnlineSampleJudge(scores=[0.9, 0.6])
    service = CollectOnlineSampleService(
        container.online_sample_repository, judge, container.audit_record_repository, container.clock, container.telemetry,
    )
    service.collect(_command())
    service.collect(_command())

    report = service.score_pending(batch_size=10)
    assert report.scored == 2
    assert report.mean_composite_score == pytest.approx((0.9 + 0.6) / 2)
    assert judge.call_count == 2

    # A second pass finds nothing left QUEUED.
    second_report = service.score_pending(batch_size=10)
    assert second_report.scored == 0
    assert second_report.mean_composite_score is None


@pytest.mark.unit
def test_find_samples_for_candidate_only_returns_that_candidates_own_samples(container: Container) -> None:
    candidate_id = CandidateId.new_id()
    other_candidate_id = CandidateId.new_id()
    container.collect_online_sample_service.collect(_command(candidate_id))
    container.collect_online_sample_service.collect(_command(other_candidate_id))
    container.collect_online_sample_service.collect(_command(None))

    found = container.collect_online_sample_service.find_samples_for_candidate(candidate_id)
    assert len(found) == 1
    assert found[0].candidate_id == candidate_id


@pytest.mark.unit
def test_a_failed_judge_call_still_reaches_scored_with_unscored_failure_code(container: Container) -> None:
    class _AlwaysFailsJudge:
        def grade(self, sample):  # noqa: ANN001, ARG002
            return GraderResult(
                dimension=EvaluationDimension.HANDOFF_COMPLETENESS, score=0.0, threshold=0.0, grader_type=GraderType.LLM_JUDGE,
                grader_version="fake-online-sample-judge-v1", failure_code=ScoreFailureCode.UNSCORED,
            )

    service = CollectOnlineSampleService(
        container.online_sample_repository, _AlwaysFailsJudge(), container.audit_record_repository, container.clock,
        container.telemetry,
    )
    view = service.collect(_command())
    report = service.score_pending(batch_size=10)
    assert report.scored == 1
    assert report.mean_composite_score is None

    persisted = container.online_sample_repository.find_by_id(view.sample_id)
    assert persisted.status == OnlineSampleStatus.SCORED
    assert persisted.failure_code == ScoreFailureCode.UNSCORED
