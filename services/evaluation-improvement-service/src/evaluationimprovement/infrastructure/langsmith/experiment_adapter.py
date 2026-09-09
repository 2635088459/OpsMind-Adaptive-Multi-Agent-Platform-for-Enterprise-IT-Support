"""13-package-and-class-design `infrastructure/langsmith/experiment_adapter.py`.
SPEC-EI-001 built NoOpLangSmithExperimentAdapter; SPEC-EI-013 adds
SdkLangSmithExperimentAdapter, the real LangSmith Experiment (Project) mapping —
see dataset_adapter's own module docstring for why `client` stays untyped here too.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from datetime import datetime, timedelta, timezone

from evaluationimprovement.application.records import LangSmithCaseResult
from evaluationimprovement.infrastructure.langsmith.dataset_adapter import SdkLangSmithDatasetAdapter

logger = logging.getLogger("evaluationimprovement.infrastructure.langsmith")


class NoOpLangSmithExperimentAdapter:
    def link_experiment(self, run_key: str, dataset_name: str, dataset_version: str) -> str | None:  # noqa: ARG002
        return None

    def push_run_results(self, experiment_ref: str, run_key: str, cases: Sequence[LangSmithCaseResult]) -> int:  # noqa: ARG002
        return 0


class SdkLangSmithExperimentAdapter:
    """04-use-cases UC-EI-002 step 4: "07 收集 LangSmith experiment." LangSmith's own
    closest concept to a named "experiment run" is a Project bound to a reference
    dataset (`client.create_project(reference_dataset_id=...)`) — no real evaluate()
    call happens here (this domain never sends prompts through LangSmith's own runner;
    Agent Runtime's own execution is the thing under test), so linkage is exactly
    that: create/reuse the Project, return its id as the opaque experiment reference.

    push_run_results (SPEC-EI-013 follow-up) then mirrors the finished, already-scored
    result set INTO that Project: one LangSmith run per test case carrying the case's
    token cost + latency, plus one feedback entry per graded dimension, so the
    LangSmith UI shows per-dimension scores, pass rates and token/cost aggregates. It
    still never runs the agent — the runs it creates are records of an execution this
    service already captured, not new invocations.
    """

    def __init__(self, client: object, dataset_adapter: SdkLangSmithDatasetAdapter) -> None:
        self._client = client
        self._dataset_adapter = dataset_adapter

    def link_experiment(self, run_key: str, dataset_name: str, dataset_version: str) -> str | None:
        reference_dataset_id = self._dataset_adapter.link_dataset(dataset_name, dataset_version)
        if reference_dataset_id is None:
            return None
        project_name = f"opsmind-eval-{dataset_name}-{dataset_version}-{run_key}"
        try:
            project = self._client.create_project(project_name=project_name, reference_dataset_id=reference_dataset_id)
            return str(project.id)
        except Exception:
            logger.warning("LangSmith experiment link failed for %s", project_name, exc_info=True)
            return None

    def push_run_results(self, experiment_ref: str, run_key: str, cases: Sequence[LangSmithCaseResult]) -> int:
        try:
            project = self._client.read_project(project_id=experiment_ref)
            project_name = project.name
        except Exception:
            logger.warning("LangSmith push: project %s not readable", experiment_ref, exc_info=True)
            return 0

        pushed = 0
        for case in cases:
            run_id = uuid.uuid4()
            end = datetime.now(timezone.utc)
            start = end - timedelta(milliseconds=max(0, case.latency_ms))
            try:
                self._client.create_run(
                    name=case.case_key,
                    inputs={"scenario": case.scenario, "run_key": run_key},
                    run_type="llm",
                    project_name=project_name,
                    id=run_id,
                    start_time=start,
                    end_time=end,
                    outputs={
                        "classification": case.classification,
                        "final_state": case.final_state,
                        "tool_calls": list(case.tool_calls),
                        "usage_metadata": {
                            "input_tokens": case.prompt_tokens,
                            "output_tokens": case.completion_tokens,
                            "total_tokens": case.total_tokens,
                        },
                    },
                    prompt_tokens=case.prompt_tokens,
                    completion_tokens=case.completion_tokens,
                    total_tokens=case.total_tokens,
                )
                for dimension, score, _passed in case.dimension_scores:
                    self._client.create_feedback(run_id=run_id, key=dimension, score=score, session_id=experiment_ref)
                pushed += 1
            except Exception:
                logger.warning("LangSmith push failed for case %s in project %s", case.case_key, experiment_ref, exc_info=True)

        try:
            self._client.flush()
        except Exception:  # noqa: S110 — flush is best-effort; the create calls above already queued the data
            pass
        return pushed
