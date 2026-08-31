"""SPEC-EI-023 (failure-clustering-root-cause-taxonomy) / phase-05, LLD mapping
04-use-cases UC-EI-003 step 4 ("Gate failed 时生成 failure clusters") and UC-EI-004
step 1 ("07 从失败 case...中归类 failure cluster"). Not among 13-package-and-class-
design's ten named services — added the same way grader_catalog.py was: a read
surface with no dedicated aggregate/table of its own (SPEC-EI-023's own
persistence_CN.md names no new table), derived at query time from each failed
EvaluationScore already on the run.

Root cause taxonomy: `(dimension, failure_code)` — the same two fields
EvaluationScore already carries per 01-domain-model, grouped rather than persisted
again. A deterministic grader that judges score-below-threshold ordinarily leaves
`failure_code` unset (only GRADER_ERROR/UNSCORED/STALE_RESULT are ever explicitly
set — see infrastructure.graders.deterministic and domain.enums.ScoreFailureCode's
own docstring), so a `None` code here is normalized to the synthesized
THRESHOLD_NOT_MET member ScoreFailureCode already reserves for exactly this case.
`cluster_id` is that pair rendered as a stable string (`"{dimension}:{failure_code}"`),
not a random UUID — the same taxonomy category always reduces to the same id, which
is what lets CreateImprovementCandidateCommand.source_failure_cluster_id and this
service's own output actually correlate.
"""

from __future__ import annotations

from collections import defaultdict

from evaluationimprovement.application.exceptions import RunNotFoundException
from evaluationimprovement.application.ports_out import EvaluationRunRepository, ScoreRepository
from evaluationimprovement.application.views import FailureClusterView
from evaluationimprovement.domain.enums import ScoreFailureCode
from evaluationimprovement.domain.ids import RunId, TestCaseId


class ClusterRunFailuresService:
    def __init__(self, run_repository: EvaluationRunRepository, score_repository: ScoreRepository) -> None:
        self._run_repository = run_repository
        self._score_repository = score_repository

    def list_clusters(self, run_id: RunId) -> tuple[FailureClusterView, ...]:
        if self._run_repository.find_by_id(run_id) is None:
            raise RunNotFoundException(run_id)

        groups: dict[tuple[str, ScoreFailureCode], list[TestCaseId]] = defaultdict(list)
        for score in self._score_repository.find_active_by_run(run_id):
            if score.passed:
                continue
            failure_code = score.failure_code or ScoreFailureCode.THRESHOLD_NOT_MET
            groups[(score.dimension.value, failure_code)].append(score.test_case_id)

        clusters = [
            FailureClusterView(
                cluster_id=f"{dimension}:{failure_code.value}", run_id=run_id, dimension=dimension,
                failure_code=failure_code, case_count=len(test_case_ids), test_case_ids=tuple(test_case_ids),
            )
            for (dimension, failure_code), test_case_ids in groups.items()
        ]
        return tuple(sorted(clusters, key=lambda c: c.cluster_id))
