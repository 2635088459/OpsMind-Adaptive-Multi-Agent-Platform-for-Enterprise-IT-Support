"""SPEC-EI-029 (promotion-criteria-rollback-request): EvaluateCanaryPromotionService,
the sole implementation of CanaryPromotionUseCase. 02-business-invariants INV-EI-010:
"Canary 扩流必须有明确阈值、时间窗和自动回滚条件" — ManageCanaryService.advance()'s own
docstring already named the gap this closes: "The caller...is responsible for having
already verified the current stage's own threshold/time-window before calling."
This service is that verification, finally a real implementation instead of an
unenforced caller obligation.

Rollback-request itself (`ManageCanaryUseCase.request_rollback()`,
`ImprovementRollbackRequested`) was already SPEC-EI-027/phase-05 scope and is
unchanged here — this spec's own gap was Promotion Criteria only.
"""

from __future__ import annotations

from opentelemetry import trace

from evaluationimprovement.application.exceptions import CandidateNotFoundException
from evaluationimprovement.application.ports_out import ImprovementCandidateRepository, OnlineSampleRepository
from evaluationimprovement.application.views import CanaryPromotionDecisionView
from evaluationimprovement.domain.enums import CandidateStatus, CanaryStatus, OnlineSampleStatus
from evaluationimprovement.domain.ids import CandidateId
from evaluationimprovement.domain.improvement_candidate import ImprovementCandidate

# 02-business-invariants doc has no named numeric quality bar for online samples —
# this mirrors AnthropicQualityJudge/AnthropicOnlineSampleJudge's own threshold=0.7
# used for the *scoring* pass/fail line; this constant is deliberately looser (0.5),
# since "error rate" here means "clearly bad," not "didn't clear the strict quality
# gate" — the same distinction failure_code (a real grading error) already draws from
# an ordinary below-threshold score.
_QUALITY_FAILURE_SCORE = 0.5

# canary_status only ever holds ACTIVE or EXPANDING while traffic is actually live
# (03-state-machine §"Canary"), regardless of how many CanaryStage entries a plan
# declares — the same simplification ManageCanaryService.advance() already makes
# (ACTIVE -> EXPANDING -> SUCCEEDED, "it never skips a stage"). This maps each onto
# the plan's own first/second stage; a plan with only one stage reuses it for both.
_STAGE_INDEX_BY_CANARY_STATUS = {CanaryStatus.ACTIVE: 0, CanaryStatus.EXPANDING: 1}
tracer = trace.get_tracer(__name__)


class EvaluateCanaryPromotionService:
    def __init__(self, candidate_repository: ImprovementCandidateRepository, online_sample_repository: OnlineSampleRepository) -> None:
        self._candidate_repository = candidate_repository
        self._online_sample_repository = online_sample_repository

    def evaluate(self, candidate_id: CandidateId) -> CanaryPromotionDecisionView:
        with tracer.start_as_current_span("CanaryManager.evaluate"):
            return self._evaluate_traced(candidate_id)

    def _evaluate_traced(self, candidate_id: CandidateId) -> CanaryPromotionDecisionView:
        candidate = self._require_candidate(candidate_id)
        if candidate.status != CandidateStatus.CANARYING or candidate.canary_status not in _STAGE_INDEX_BY_CANARY_STATUS:
            return CanaryPromotionDecisionView(
                candidate_id=candidate_id, eligible_to_advance=False, recommend_rollback=False, sample_count=0,
                required_sample_size=0, error_rate=None,
                reason=f"candidate is not actively canarying (status={candidate.status.value}, canary_status="
                       f"{candidate.canary_status.value if candidate.canary_status else None})",
            )

        stage_index = min(_STAGE_INDEX_BY_CANARY_STATUS[candidate.canary_status], candidate.canary_plan.stage_count - 1)
        stage = candidate.canary_plan.stage_at(stage_index)

        samples = self._online_sample_repository.find_by_candidate(candidate_id)
        scored = [s for s in samples if s.status == OnlineSampleStatus.SCORED]

        if len(scored) < stage.sample_size:
            return CanaryPromotionDecisionView(
                candidate_id=candidate_id, eligible_to_advance=False, recommend_rollback=False, sample_count=len(scored),
                required_sample_size=stage.sample_size, error_rate=None,
                reason=f"insufficient online samples: {len(scored)}/{stage.sample_size} required before this stage can advance",
            )

        error_count = sum(1 for s in scored if s.failure_code is not None or (s.composite_score or 0.0) < _QUALITY_FAILURE_SCORE)
        error_rate = error_count / len(scored)

        if error_rate > stage.rollback_error_rate_threshold:
            return CanaryPromotionDecisionView(
                candidate_id=candidate_id, eligible_to_advance=False, recommend_rollback=True, sample_count=len(scored),
                required_sample_size=stage.sample_size, error_rate=error_rate,
                reason=f"online sample error rate {error_rate:.2%} exceeds this stage's own rollback threshold "
                       f"{stage.rollback_error_rate_threshold:.2%}",
            )

        return CanaryPromotionDecisionView(
            candidate_id=candidate_id, eligible_to_advance=True, recommend_rollback=False, sample_count=len(scored),
            required_sample_size=stage.sample_size, error_rate=error_rate,
            reason=f"online sample error rate {error_rate:.2%} is within this stage's own rollback threshold "
                   f"{stage.rollback_error_rate_threshold:.2%}",
        )

    def _require_candidate(self, candidate_id: CandidateId) -> ImprovementCandidate:
        candidate = self._candidate_repository.find_by_id(candidate_id)
        if candidate is None:
            raise CandidateNotFoundException(candidate_id)
        return candidate
