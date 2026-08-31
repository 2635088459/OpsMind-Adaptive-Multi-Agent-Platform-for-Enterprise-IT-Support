"""SPEC-EI-033 (observability-evaluation-signal-contract) / 07-evaluation-improvement's
own 12-observability doc: the Metrics/Logs/Traces field contract this domain's own
signals must carry — checked against what EvaluationTelemetry/the pipeline services
actually emit, mirroring test_event_envelope_contract.py's own "a schema drift here
breaks CI" precedent for outbound events.
"""

from __future__ import annotations

import logging

import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CompareRegressionCommand,
    CreateDatasetCommand,
    CreateImprovementCandidateCommand,
    CreateRunCommand,
    EvaluateReleaseGateCommand,
    ExecuteCaseCommand,
    FinalizeRunScoringCommand,
    PublishDatasetCommand,
    ScoreCaseCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CandidateType, Criticality, RiskLevel
from evaluationimprovement.domain.ids import IdempotencyKey, RunId
from evaluationimprovement.infrastructure.observability import configure_observability
from evaluationimprovement.settings import get_settings

# Idempotent (configure_observability's own `_configured` guard) — ensures the real
# SDK TracerProvider is set even if this is the only test module pytest imports, the
# same precondition main.py's own `app = create_app()` establishes at import time.
configure_observability(get_settings())

# 12-observability §"Metrics" — the eleven instruments that doc names.
_LLD_NAMED_METRICS = (
    "evaluation_run_total", "evaluation_case_total", "evaluation_gate_pass_total", "evaluation_gate_fail_total",
    "evaluation_score", "evaluation_regression_total", "improvement_candidate_total", "canary_rollback_total",
    "grader_error_total", "evaluation_cost_tokens_total", "evaluation_latency_seconds",
)

# 12-observability §"Traces" — the seven "关键 span" that doc names.
_LLD_NAMED_SPANS = (
    "EvaluationRunService.createRun", "CaseRunner.executeCase", "GraderRegistry.grade", "RegressionComparator.compare",
    "ReleaseGateEvaluator.evaluate", "ImprovementCandidateService.create", "CanaryManager.evaluate",
)


def _publish_dataset(container: Container, name: str):
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=name, version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1", actor="author-1",
        correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="Duo enrollment expired", user_request_redacted="mfa broken", mock_system_state={},
        ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"}, allowed_tools=("reset_duo_enrollment",), forbidden_tools=(),
        required_approval=False, verification_condition={}, criticality=Criticality.CRITICAL,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    return published, added[0].test_case_id


def _drive_run_to_scored(container: Container, run_key: str) -> tuple[RunId, str]:
    published, test_case_id = _publish_dataset(container, f"observability-{run_key}")
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key=run_key, dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.execute_case(ExecuteCaseCommand(run_id=run.run_id, test_case_id=test_case_id, attempt=1, actor="ci", correlation_id="corr-1"))
    container.score_run_service.score_case(ScoreCaseCommand(run_id=run.run_id, test_case_id=test_case_id, run_generation=1, actor="ci", correlation_id="corr-1"))
    return run.run_id, str(test_case_id)


@pytest.mark.unit
def test_every_lld_named_metric_is_wired(container: Container) -> None:
    """A smoke check, not a real export assertion (OpenTelemetry's global provider
    replacement is one-shot — see this module's own span test for the exporter
    workaround that trick doesn't extend to metrics readers): every LLD-named
    instrument has a real `record_*` counterpart on EvaluationTelemetry, and calling
    it never raises.
    """
    telemetry = container.telemetry
    telemetry.record_run_status("PASSED", "dataset-1", "agent-runtime:rc1")
    telemetry.record_case_result("PASSED", "CRITICAL")
    telemetry.record_gate_passed("mvp-release-gate-v1")
    telemetry.record_gate_failed("mvp-release-gate-v1", "critical_case_failed")
    telemetry.record_score("CLASSIFICATION_ACCURACY", "1", "agent-runtime:rc1", 0.95)
    telemetry.record_regression("CLASSIFICATION_ACCURACY", "high")
    telemetry.record_candidate("PROMPT_CHANGE", "DRAFT", "MEDIUM")
    telemetry.record_canary_rollback("error_rate_spike")
    telemetry.record_grader_error("DETERMINISTIC", "classification-accuracy-v1")
    telemetry.record_cost_tokens("agent-runtime:rc1", "agent-runtime:rc1", 100)
    telemetry.record_stage_latency("EXECUTE", 0.5)
    assert _LLD_NAMED_METRICS  # documents the full contract this test exercises above


@pytest.mark.unit
def test_score_case_log_line_carries_every_required_field(container: Container, caplog: pytest.LogCaptureFixture) -> None:
    """12-observability §"Logs": runId/testCaseId/datasetVersion/targetVersion/
    graderVersion/traceId/correlationId/failureCode.
    """
    with caplog.at_level(logging.INFO, logger="evaluationimprovement.application.services.score_run"):
        run_id, test_case_id = _drive_run_to_scored(container, "log-contract-001")

    matching = [r for r in caplog.records if r.message.startswith("action=score_case")]
    assert matching, "score_case must emit a structured log line"
    line = matching[-1].message
    for field, value in (
        ("run_id", str(run_id)), ("test_case_id", test_case_id), ("dataset_version", "1"),
        ("target_version", "agent-runtime:rc1"), ("correlation_id", "corr-1"),
    ):
        assert f"{field}={value}" in line, f"missing {field} in: {line}"
    for field in ("grader_version", "trace_id", "failure_code"):
        assert f"{field}=" in line, f"missing {field} in: {line}"


@pytest.mark.unit
def test_create_candidate_log_line_carries_the_candidate_id(container: Container, caplog: pytest.LogCaptureFixture) -> None:
    run_id, _ = _drive_run_to_scored(container, "log-contract-002")
    with caplog.at_level(logging.INFO, logger="evaluationimprovement.application.services.create_improvement_candidate"):
        candidate = container.create_improvement_candidate_service.create(CreateImprovementCandidateCommand(
            candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=run_id, source_failure_cluster_id="c1",
            target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
            created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("idem-log-contract-1"),
        ))

    matching = [r for r in caplog.records if r.message.startswith("action=create_candidate")]
    assert matching
    line = matching[-1].message
    assert f"candidate_id={candidate.candidate_id}" in line
    assert "trace_id=" in line
    assert "correlation_id=corr-1" in line


@pytest.mark.unit
def test_named_spans_are_created_for_the_pipeline(container: Container) -> None:
    """12-observability §"Traces": the seven "关键 span" — captured via a second span
    processor added onto the already-configured global TracerProvider
    (`add_span_processor()` composes; only `set_tracer_provider()` itself is
    one-shot, which is why this test does not attempt to replace the provider).
    """
    exporter = InMemorySpanExporter()
    trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(exporter))
    exporter.clear()

    published, test_case_id = _publish_dataset(container, "span-contract-001")
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="span-contract-001", dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    container.execute_case_service.execute_case(ExecuteCaseCommand(run_id=run.run_id, test_case_id=test_case_id, attempt=1, actor="ci", correlation_id="corr-1"))
    container.score_run_service.score_case(ScoreCaseCommand(run_id=run.run_id, test_case_id=test_case_id, run_generation=1, actor="ci", correlation_id="corr-1"))
    finalized = container.score_run_service.finalize_scoring(FinalizeRunScoringCommand(run_id=run.run_id, actor="ci", correlation_id="corr-1"))
    assert finalized.status.value == "COMPARING"
    container.compare_regression_service.compare(CompareRegressionCommand(run_id=run.run_id, baseline_run_id=None, actor="ci", correlation_id="corr-1"))
    container.evaluate_release_gate_service.evaluate(EvaluateReleaseGateCommand(run_id=run.run_id, gate_policy="mvp-release-gate-v1", actor="ci", correlation_id="corr-1"))

    candidate = container.create_improvement_candidate_service.create(CreateImprovementCandidateCommand(
        candidate_type=CandidateType.PROMPT_CHANGE, source_run_id=run.run_id, source_failure_cluster_id="c1",
        target_component="identity-agent-prompt", proposed_change={"promptDiff": "..."}, risk_level=RiskLevel.MEDIUM,
        created_by="author-1", actor="author-1", correlation_id="corr-1", idempotency_key=IdempotencyKey("idem-span-contract-1"),
    ))
    container.evaluate_canary_promotion_service.evaluate(candidate.candidate_id)

    span_names = {s.name for s in exporter.get_finished_spans()}
    missing = [name for name in _LLD_NAMED_SPANS if name not in span_names]
    assert not missing, f"missing required spans: {missing} (present: {sorted(span_names)})"
