"""SPEC-EI-028 (online-sample-evaluation): CollectOnlineSampleService, the sole
implementation of OnlineSampleUseCase and OnlineSampleScoringPort. 04-use-cases
UC-EI-006: "07 消费 workflow completed、ticket reopened、tool failed、approval denied
等事件，根据 sampling policy 选择 trace，脱敏后写入 online evaluation queue，对解释质量、证据
完整性、handoff completeness 和 user clarity 做延迟评分，输出 trend metric，不直接阻塞业务链路."

Steps 1-2 (consuming those cross-domain events and applying the sampling policy that
selects a trace) are SPEC-EI-030's own cross-domain-contract scope, phase-07 — see
CollectOnlineSampleCommand's own docstring. This service owns steps 3-5: collect an
already-selected, already-redacted trace into a queue, delayed-score it, and emit a
trend metric — never a gate, never blocking.
"""

from __future__ import annotations

import uuid

from evaluationimprovement.application.commands import CollectOnlineSampleCommand
from evaluationimprovement.application.ports_out import (
    AuditRecordRepository,
    ClockPort,
    OnlineSampleQualityJudgePort,
    OnlineSampleRepository,
)
from evaluationimprovement.application.records import OnlineEvaluationSample
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.telemetry import EvaluationTelemetry
from evaluationimprovement.application.views import OnlineEvaluationSampleView, OnlineSampleScoringReport
from evaluationimprovement.domain.enums import OnlineSampleStatus
from evaluationimprovement.domain.ids import CandidateId


class CollectOnlineSampleService:
    def __init__(
        self, online_sample_repository: OnlineSampleRepository, quality_judge: OnlineSampleQualityJudgePort,
        audit_record_repository: AuditRecordRepository, clock: ClockPort, telemetry: EvaluationTelemetry,
    ) -> None:
        self._online_sample_repository = online_sample_repository
        self._quality_judge = quality_judge
        self._clock = clock
        self._telemetry = telemetry
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def collect(self, command: CollectOnlineSampleCommand) -> OnlineEvaluationSampleView:
        now = self._clock.now()
        sample = OnlineEvaluationSample(
            sample_id=uuid.uuid4(), candidate_id=command.candidate_id.value if command.candidate_id else None,
            target_version=command.target_version, source_event_type=command.source_event_type,
            source_trace_ref=command.source_trace_ref, redacted_context=command.redacted_context,
            status=OnlineSampleStatus.QUEUED, collected_at=now,
        )
        saved = self._online_sample_repository.save(sample)
        self._audit_recorder.record(
            action="collect_online_sample", resource_type="ONLINE_EVALUATION_SAMPLE", resource_id=str(saved.sample_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return _sample_to_view(saved)

    def score_pending(self, batch_size: int) -> OnlineSampleScoringReport:
        """SPEC-EI-028 / UC-EI-006 step 4: an operational surface, not a REST-exposed
        use case — mirrors OutboxDispatchPort.dispatch_due_events()/CaseRunnerPort.
        run_once() exactly (both are also invoked directly, never through a REST
        endpoint). Never raises on an individual judge failure: AnthropicOnlineSampleJudge
        itself fails open into an UNSCORED GraderResult (see that class's own
        docstring), so every sample in the batch still reaches SCORED.
        """
        queued = self._online_sample_repository.find_queued(batch_size)
        now = self._clock.now()
        scores: list[float] = []
        for sample in queued:
            result = self._quality_judge.grade(sample)
            scored = OnlineEvaluationSample(
                sample_id=sample.sample_id, candidate_id=sample.candidate_id, target_version=sample.target_version,
                source_event_type=sample.source_event_type, source_trace_ref=sample.source_trace_ref,
                redacted_context=sample.redacted_context, status=OnlineSampleStatus.SCORED, collected_at=sample.collected_at,
                scored_at=now, composite_score=result.score, score_details=result.details, failure_code=result.failure_code,
            )
            self._online_sample_repository.save(scored)
            if result.failure_code is None:
                scores.append(result.score)
                self._telemetry.record_online_sample_scored(sample.source_event_type, result.score)
        mean_score = sum(scores) / len(scores) if scores else None
        return OnlineSampleScoringReport(scored=len(queued), mean_composite_score=mean_score)

    def find_samples_for_candidate(self, candidate_id: CandidateId) -> tuple[OnlineEvaluationSampleView, ...]:
        return tuple(_sample_to_view(s) for s in self._online_sample_repository.find_by_candidate(candidate_id))


def _sample_to_view(sample: OnlineEvaluationSample) -> OnlineEvaluationSampleView:
    return OnlineEvaluationSampleView(
        sample_id=sample.sample_id, candidate_id=CandidateId(sample.candidate_id) if sample.candidate_id else None,
        target_version=sample.target_version, source_event_type=sample.source_event_type,
        source_trace_ref=sample.source_trace_ref, status=sample.status, collected_at=sample.collected_at,
        scored_at=sample.scored_at, composite_score=sample.composite_score, failure_code=sample.failure_code,
    )
