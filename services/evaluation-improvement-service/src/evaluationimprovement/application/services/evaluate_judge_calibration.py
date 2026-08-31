"""13-package-and-class-design §"应用层" (pragmatic extension — see
application.ports_out.JudgeBundleStatusRepository's own docstring):
EvaluateJudgeCalibrationService. SPEC-EI-018 (judge-calibration-drift-guard) /
10-failure-handling §"Judge drift": "同一 judge bundle 对固定 calibration set 超出阈值时禁用该
bundle." An operational surface (mirrors DispatchOutboxEventsService: no REST route of
its own, an ops/CI job or admin trigger calls it directly) rather than a domain use
case — the calibration set itself is caller-supplied, not persisted.
"""

from __future__ import annotations

from evaluationimprovement.application.ports_out import ClockPort, JudgeBundleStatusRepository
from evaluationimprovement.application.records import JudgeBundleStatus, JudgeCalibrationCase
from evaluationimprovement.application.telemetry import EvaluationTelemetry

# 10-failure-handling names no fixed number — a mean-absolute-error tolerance wide
# enough to absorb ordinary judge-to-judge scoring noise, tight enough to still catch
# a genuinely drifted bundle (e.g. one that started scoring everything near 1.0).
_DEFAULT_DRIFT_THRESHOLD = 0.15


class EvaluateJudgeCalibrationService:
    def __init__(self, judge_bundle_status_repository: JudgeBundleStatusRepository, clock: ClockPort, telemetry: EvaluationTelemetry) -> None:
        self._judge_bundle_status_repository = judge_bundle_status_repository
        self._clock = clock
        self._telemetry = telemetry

    def evaluate(
        self, judge, calibration_cases: tuple[JudgeCalibrationCase, ...], drift_threshold: float = _DEFAULT_DRIFT_THRESHOLD,  # noqa: ANN001 - infrastructure.graders judge grader, kept untyped to avoid an application->infrastructure import
    ) -> JudgeBundleStatus:
        """Grades every calibration case with `judge` directly (never through
        GraderRegistry — that would recurse into this same drift gate) and compares
        each live score against its own `expected_score`. A judge call that itself
        fails (network/parse error) counts as maximum deviation (1.0) rather than
        being skipped — an unreachable judge cannot be presumed calibrated.
        """
        if not calibration_cases:
            raise ValueError("calibration_cases must not be empty")

        errors: list[float] = []
        for case in calibration_cases:
            grader_result = judge.grade(case.test_case, case.result)
            if grader_result.failure_code is not None:
                errors.append(1.0)
            else:
                errors.append(abs(grader_result.score - case.expected_score))
        mean_absolute_error = sum(errors) / len(errors)

        enabled = mean_absolute_error <= drift_threshold
        status = JudgeBundleStatus(
            grader_version=judge.version, enabled=enabled, last_checked_at=self._clock.now(),
            last_mean_absolute_error=mean_absolute_error,
            disabled_reason=None if enabled else f"calibration drift {mean_absolute_error:.3f} exceeds threshold {drift_threshold:.3f}",
        )
        saved = self._judge_bundle_status_repository.save_status(status)
        if not enabled:
            self._telemetry.record_judge_calibration_drift(judge.version)
        return saved
