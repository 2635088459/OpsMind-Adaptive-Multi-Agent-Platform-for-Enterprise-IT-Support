"""Shared domain value objects — used across evaluation_run.py, score.py,
regression_report.py, and improvement_candidate.py alike, so they are factored out
here rather than owned by any single aggregate module (mirrors
memory-knowledge-service's own domain.values convention).
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class VersionBinding:
    """02-business-invariants INV-EI-006: "所有评估事实必须绑定 dataset version、target
    version、grader version、policy version/input hash 和 correlation id." Carried by
    EvaluationRun and echoed onto every EvaluationScore/RegressionReport it produces.
    """

    dataset_version: str
    target_version: str
    grader_bundle_version: str
    policy_version: str
    correlation_id: str
    baseline_version: str | None = None

    def __post_init__(self) -> None:
        for name, val in (
            ("datasetVersion", self.dataset_version), ("targetVersion", self.target_version),
            ("graderBundleVersion", self.grader_bundle_version), ("policyVersion", self.policy_version),
            ("correlationId", self.correlation_id),
        ):
            if not val or not val.strip():
                raise ValueError(f"{name} must not be blank")


@dataclass(frozen=True, slots=True)
class EvidenceRef:
    """07-data-model §"Artifact 引用": LangSmith experiment/trace/judge-explanation/
    large-report artifacts are never stored inline, only referenced.
    """

    artifact_provider: str
    artifact_uri: str
    artifact_hash: str
    retention_until: str | None = None


@dataclass(frozen=True, slots=True)
class GateResult:
    """01-domain-model §"RegressionReport": `gateResults`. One row per gate check
    (critical-case pass rate, policy-violation count, forbidden-tool count, ...).
    """

    gate_name: str
    passed: bool
    reason: str = ""


@dataclass(frozen=True, slots=True)
class MetricDiff:
    """01-domain-model §"RegressionReport": `metricDiffs`."""

    dimension: str
    baseline_value: float
    candidate_value: float

    @property
    def delta(self) -> float:
        return self.candidate_value - self.baseline_value


@dataclass(frozen=True, slots=True)
class CanaryStage:
    """03-state-machine §"Canary" / 02-business-invariants INV-EI-010: "Canary 扩流必须
    有明确阈值、时间窗和自动回滚条件."
    """

    traffic_percent: float
    min_duration_minutes: int
    rollback_error_rate_threshold: float
    # SPEC-EI-027 (canary-plan-rollout-state-machine) / phase-06 own "强制约束":
    # "Canary 必须有流量比例、时间窗、sample size 和 rollback thresholds" — the fourth of
    # those four required fields; the minimum number of online samples this stage's
    # own promotion criteria (SPEC-EI-029) requires before advancing past it, so a
    # stage can never "pass" on too few observations to mean anything.
    sample_size: int = 1

    def __post_init__(self) -> None:
        if not (0 < self.traffic_percent <= 100):
            raise ValueError("trafficPercent must be within (0, 100]")
        if self.min_duration_minutes <= 0:
            raise ValueError("minDurationMinutes must be positive")
        if self.sample_size <= 0:
            raise ValueError("sampleSize must be positive")


@dataclass(frozen=True, slots=True)
class CanaryPlan:
    """01-domain-model §"ImprovementCandidate": `canaryPlan` (jsonb). `plan_version` is
    the value 09-concurrency-and-idempotency's own Canary-operation idempotency key
    (`candidateId:canaryPlanVersion:operation`) embeds.
    """

    plan_version: str
    stages: tuple[CanaryStage, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if not self.plan_version or not self.plan_version.strip():
            raise ValueError("planVersion must not be blank")
        if not self.stages:
            raise ValueError("a canary plan must declare at least one stage")

    def stage_at(self, index: int) -> CanaryStage:
        return self.stages[index]

    @property
    def stage_count(self) -> int:
        return len(self.stages)
