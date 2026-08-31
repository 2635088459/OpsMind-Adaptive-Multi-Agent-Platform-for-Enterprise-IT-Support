"""Composition root. FastAPI has no classpath-scanning DI container the way Spring
does; this module is the one place allowed to import both
evaluationimprovement.infrastructure and evaluationimprovement.application, construct
the singleton graph, and hand out evaluationimprovement.application.ports_in
implementations to the interfaces layer via FastAPI's Depends(). No business rule
lives here — pure wiring, mirroring memory-knowledge-service's own container.py
exactly.

SPEC-EI-002 introduced the real Postgres-backed adapters
(infrastructure.persistence.postgres) for the six core aggregate repositories plus
the two pragmatic extensions (GatePolicyRepository, CaseExecutionResultRepository).
SPEC-EI-003 adds the remaining four (OutboxRepository, ProcessedEventRepository,
CommandIdempotencyRepository, AuditRecordRepository) the same way, alongside
SPEC-EI-001's own in-memory ones. evaluationimprovement.settings.Settings.
evaluation_persistence picks between them all together — "postgres" (the default,
for real runs) or "memory" (fast, hermetic tests set this explicitly via
tests/conftest.py; unit tests that construct services directly, bypassing this
container entirely, are unaffected either way). EventPublisherPort itself stays
LoggingEventPublisherAdapter regardless — its own real RabbitMQ wiring is a later
spec's job, see infrastructure.event_publisher's own module docstring.
"""

from __future__ import annotations

import logging
from functools import lru_cache

from evaluationimprovement.application.ports_in import (
    AdminRecoveryUseCase,
    ApprovalDecisionEventConsumerPort,
    AuditQueryUseCase,
    CandidateQueryUseCase,
    CanaryPromotionUseCase,
    CaseRunnerPort,
    CompareRegressionUseCase,
    CreateDatasetUseCase,
    CreateImprovementCandidateUseCase,
    CreateRunUseCase,
    CrossDomainEventConsumerPort,
    DatasetQueryUseCase,
    EvaluateReleaseGateUseCase,
    ExecuteCaseUseCase,
    FailureClusterQueryUseCase,
    GatePolicyUseCase,
    GraderCatalogUseCase,
    ManageCanaryUseCase,
    OnlineSampleScoringPort,
    OnlineSampleUseCase,
    OutboxDispatchPort,
    PoisonEventQueryUseCase,
    PublishDatasetUseCase,
    ReportQueryUseCase,
    RunQueryUseCase,
    ScoreRunUseCase,
)
from evaluationimprovement.application.ports_out import (
    AgentRuntimeEvaluationPort,
    LangSmithPort,
    OnlineSampleQualityJudgePort,
    PolicyApprovalPort,
)
from evaluationimprovement.application.services.admin_recovery import AdminRecoveryService
from evaluationimprovement.application.services.audit_query import AuditRecordQueryService
from evaluationimprovement.application.services.ci_evaluation_gate import CiEvaluationGateService
from evaluationimprovement.application.services.cluster_run_failures import ClusterRunFailuresService
from evaluationimprovement.application.services.collect_online_sample import CollectOnlineSampleService
from evaluationimprovement.application.services.compare_regression import CompareRegressionService
from evaluationimprovement.application.services.consume_approval_decision_event import ConsumeApprovalDecisionEventService
from evaluationimprovement.application.services.consume_cross_domain_event import ConsumeCrossDomainEventService
from evaluationimprovement.application.services.evaluate_canary_promotion import EvaluateCanaryPromotionService
from evaluationimprovement.application.services.poison_event_query import PoisonEventQueryService
from evaluationimprovement.application.services.create_dataset import CreateDatasetService
from evaluationimprovement.application.services.create_improvement_candidate import CreateImprovementCandidateService
from evaluationimprovement.application.services.create_run import CreateRunService
from evaluationimprovement.application.services.dispatch_outbox_events import DispatchOutboxEventsService
from evaluationimprovement.application.services.evaluate_judge_calibration import EvaluateJudgeCalibrationService
from evaluationimprovement.application.services.evaluate_release_gate import EvaluateReleaseGateService
from evaluationimprovement.application.services.execute_case import ExecuteCaseService
from evaluationimprovement.application.services.grader_catalog import GraderCatalogService
from evaluationimprovement.application.services.manage_canary import ManageCanaryService
from evaluationimprovement.application.services.publish_dataset import PublishDatasetService
from evaluationimprovement.application.services.run_case_queue import CaseRunnerService
from evaluationimprovement.application.services.score_run import ScoreRunService
from evaluationimprovement.application.telemetry import EvaluationTelemetry
from evaluationimprovement.infrastructure.clock import SystemClockAdapter
from evaluationimprovement.infrastructure.event_publisher import LoggingEventPublisherAdapter
from evaluationimprovement.infrastructure.graders.registry import GraderRegistry
from evaluationimprovement.infrastructure.langsmith.client import LangSmithClientAdapter
from evaluationimprovement.infrastructure.persistence.in_memory import (
    InMemoryAuditRecordRepository,
    InMemoryCaseExecutionQueueRepository,
    InMemoryCaseExecutionResultRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryDatasetRepository,
    InMemoryEvaluationRunRepository,
    InMemoryGatePolicyRepository,
    InMemoryImprovementCandidateRepository,
    InMemoryJudgeBundleStatusRepository,
    InMemoryLangSmithLinkRepository,
    InMemoryOnlineSampleRepository,
    InMemoryOutboxRepository,
    InMemoryPoisonEventRepository,
    InMemoryProcessedEventRepository,
    InMemoryRegressionReportRepository,
    InMemoryScoreRepository,
    InMemoryTestCaseRepository,
)
from evaluationimprovement.infrastructure.persistence.postgres.repositories import (
    PostgresAuditRecordRepository,
    PostgresCaseExecutionQueueRepository,
    PostgresCaseExecutionResultRepository,
    PostgresCommandIdempotencyRepository,
    PostgresDatasetRepository,
    PostgresEvaluationRunRepository,
    PostgresGatePolicyRepository,
    PostgresImprovementCandidateRepository,
    PostgresJudgeBundleStatusRepository,
    PostgresLangSmithLinkRepository,
    PostgresOnlineSampleRepository,
    PostgresOutboxRepository,
    PostgresPoisonEventRepository,
    PostgresProcessedEventRepository,
    PostgresRegressionReportRepository,
    PostgresScoreRepository,
    PostgresTestCaseRepository,
)
from evaluationimprovement.infrastructure.persistence.postgres.session import build_engine, build_session_factory
from evaluationimprovement.infrastructure.policy.policy_approval_client import FakePolicyApprovalAdapter, HttpPolicyApprovalAdapter
from evaluationimprovement.infrastructure.runtime.agent_runtime_client import (
    FakeAgentRuntimeEvaluationAdapter,
    HttpAgentRuntimeEvaluationAdapter,
)
from evaluationimprovement.infrastructure.security.authorization import StaticAuthorizationPolicyAdapter
from evaluationimprovement.infrastructure.telemetry.artifact_store import InMemoryTelemetryArtifactAdapter
from evaluationimprovement.settings import Settings, get_settings

logger = logging.getLogger("evaluationimprovement.container")


class _AggregateAdapters:
    """Groups every persistence.postgres-eligible repository so Container.__init__
    stays a flat, readable list of services regardless of which backend built them.
    """

    def __init__(
        self, dataset_repository, test_case_repository, run_repository, score_repository,
        case_execution_result_repository, case_execution_queue_repository, langsmith_link_repository,
        judge_bundle_status_repository, regression_report_repository, candidate_repository, gate_policy_repository,
        outbox_repository, processed_event_repository, command_idempotency_repository, audit_record_repository,
        online_sample_repository, poison_event_repository,
    ) -> None:
        self.dataset_repository = dataset_repository
        self.test_case_repository = test_case_repository
        self.run_repository = run_repository
        self.score_repository = score_repository
        self.case_execution_result_repository = case_execution_result_repository
        self.case_execution_queue_repository = case_execution_queue_repository
        self.langsmith_link_repository = langsmith_link_repository
        self.judge_bundle_status_repository = judge_bundle_status_repository
        self.regression_report_repository = regression_report_repository
        self.candidate_repository = candidate_repository
        self.gate_policy_repository = gate_policy_repository
        self.outbox_repository = outbox_repository
        self.processed_event_repository = processed_event_repository
        self.command_idempotency_repository = command_idempotency_repository
        self.audit_record_repository = audit_record_repository
        self.online_sample_repository = online_sample_repository
        self.poison_event_repository = poison_event_repository


def _build_memory_adapters() -> _AggregateAdapters:
    return _AggregateAdapters(
        dataset_repository=InMemoryDatasetRepository(), test_case_repository=InMemoryTestCaseRepository(),
        run_repository=InMemoryEvaluationRunRepository(), score_repository=InMemoryScoreRepository(),
        case_execution_result_repository=InMemoryCaseExecutionResultRepository(),
        case_execution_queue_repository=InMemoryCaseExecutionQueueRepository(),
        langsmith_link_repository=InMemoryLangSmithLinkRepository(),
        judge_bundle_status_repository=InMemoryJudgeBundleStatusRepository(),
        regression_report_repository=InMemoryRegressionReportRepository(),
        candidate_repository=InMemoryImprovementCandidateRepository(), gate_policy_repository=InMemoryGatePolicyRepository(),
        outbox_repository=InMemoryOutboxRepository(), processed_event_repository=InMemoryProcessedEventRepository(),
        command_idempotency_repository=InMemoryCommandIdempotencyRepository(), audit_record_repository=InMemoryAuditRecordRepository(),
        online_sample_repository=InMemoryOnlineSampleRepository(), poison_event_repository=InMemoryPoisonEventRepository(),
    )


def _build_postgres_adapters(settings: Settings) -> _AggregateAdapters:
    engine = build_engine(settings.sqlalchemy_url)
    session_factory = build_session_factory(engine)
    return _AggregateAdapters(
        dataset_repository=PostgresDatasetRepository(session_factory), test_case_repository=PostgresTestCaseRepository(session_factory),
        run_repository=PostgresEvaluationRunRepository(session_factory), score_repository=PostgresScoreRepository(session_factory),
        case_execution_result_repository=PostgresCaseExecutionResultRepository(session_factory),
        case_execution_queue_repository=PostgresCaseExecutionQueueRepository(session_factory),
        langsmith_link_repository=PostgresLangSmithLinkRepository(session_factory),
        judge_bundle_status_repository=PostgresJudgeBundleStatusRepository(session_factory),
        regression_report_repository=PostgresRegressionReportRepository(session_factory),
        candidate_repository=PostgresImprovementCandidateRepository(session_factory),
        gate_policy_repository=PostgresGatePolicyRepository(session_factory),
        outbox_repository=PostgresOutboxRepository(session_factory), processed_event_repository=PostgresProcessedEventRepository(session_factory),
        command_idempotency_repository=PostgresCommandIdempotencyRepository(session_factory),
        audit_record_repository=PostgresAuditRecordRepository(session_factory),
        online_sample_repository=PostgresOnlineSampleRepository(session_factory),
        poison_event_repository=PostgresPoisonEventRepository(session_factory),
    )


def _build_agent_runtime_port(settings: Settings) -> AgentRuntimeEvaluationPort:
    """Settings.agent_runtime_evaluation_mode="fake" (default) keeps every hermetic
    test's own deterministic simulator; "http" wires the real client — see
    infrastructure.runtime.agent_runtime_client's own module docstring.
    """
    if settings.agent_runtime_evaluation_mode != "http":
        return FakeAgentRuntimeEvaluationAdapter()
    import httpx

    client = httpx.Client(timeout=settings.agent_runtime_timeout_seconds)
    return HttpAgentRuntimeEvaluationAdapter(client, settings.agent_runtime_base_url)


def _build_policy_approval_port(settings: Settings) -> PolicyApprovalPort:
    """Settings.policy_approval_mode="fake" (default) keeps every hermetic test's own
    FakePolicyApprovalAdapter; "http" wires the real client — see
    infrastructure.policy.policy_approval_client's own module docstring.
    """
    if settings.policy_approval_mode != "http":
        return FakePolicyApprovalAdapter()
    import httpx

    client = httpx.Client(timeout=settings.policy_approval_timeout_seconds)
    return HttpPolicyApprovalAdapter(client, settings.policy_approval_base_url, settings.policy_approval_service_token)


def _build_quality_judge(settings: Settings) -> object:
    """Settings.llm_judge_mode="placeholder" (default) keeps ExplanationQualityJudge;
    "anthropic" wires AnthropicQualityJudge — same import-lazily/degrade-to-safe-
    default shape as `_build_langsmith_port()`. Returns `object` (not a named Protocol
    — this judge is registered inside GraderRegistry, not exposed as its own
    application-layer port).
    """
    from evaluationimprovement.infrastructure.graders.llm_judge import ExplanationQualityJudge

    if settings.llm_judge_mode != "anthropic":
        return ExplanationQualityJudge()
    try:
        import anthropic

        from evaluationimprovement.infrastructure.graders.llm_judge import AnthropicQualityJudge

        client = anthropic.Anthropic(api_key=settings.anthropic_api_key) if settings.anthropic_api_key else anthropic.Anthropic()
        return AnthropicQualityJudge(client, settings.anthropic_judge_model)
    except Exception:
        logger.warning("llm_judge_mode=anthropic but the Anthropic client could not be constructed; falling back to placeholder", exc_info=True)
        return ExplanationQualityJudge()


def _build_online_sample_judge(settings: Settings) -> OnlineSampleQualityJudgePort:
    """SPEC-EI-028: same `Settings.llm_judge_mode` flag `_build_quality_judge()`
    reads — one setting gates both judges, since they are the same real-vs-fake seam
    over the same `anthropic` client, just against two different prompt shapes (case
    grading vs. online-sample grading — see infrastructure.graders.llm_judge's own
    module docstring).
    """
    from evaluationimprovement.infrastructure.graders.llm_judge import PlaceholderOnlineSampleJudge

    if settings.llm_judge_mode != "anthropic":
        return PlaceholderOnlineSampleJudge()
    try:
        import anthropic

        from evaluationimprovement.infrastructure.graders.llm_judge import AnthropicOnlineSampleJudge

        client = anthropic.Anthropic(api_key=settings.anthropic_api_key) if settings.anthropic_api_key else anthropic.Anthropic()
        return AnthropicOnlineSampleJudge(client, settings.anthropic_judge_model)
    except Exception:
        logger.warning("llm_judge_mode=anthropic but the Anthropic client could not be constructed; falling back to placeholder", exc_info=True)
        return PlaceholderOnlineSampleJudge()


def _build_langsmith_port(settings: Settings) -> LangSmithPort:
    """Settings.langsmith_mode="noop" (default) keeps LangSmithPort.is_enabled()
    False, so EvaluateReleaseGateService's own fail-closed rule never triggers for a
    deployment that never opted in. "sdk" wires the real langsmith SDK — a missing
    package or a construction failure (bad api key, network unreachable at startup)
    degrades to the same no-op adapter rather than failing service startup, logged as
    a warning: this domain's own 10-failure-handling precedent is "fail open" for
    anything short of an actual offline-gate evaluation.
    """
    if settings.langsmith_mode != "sdk":
        return LangSmithClientAdapter(enabled=False)
    try:
        import langsmith

        from evaluationimprovement.infrastructure.langsmith.dataset_adapter import SdkLangSmithDatasetAdapter
        from evaluationimprovement.infrastructure.langsmith.experiment_adapter import SdkLangSmithExperimentAdapter

        client = langsmith.Client(api_key=settings.langsmith_api_key, api_url=settings.langsmith_api_url)
        dataset_adapter = SdkLangSmithDatasetAdapter(client)
        return LangSmithClientAdapter(experiment_adapter=SdkLangSmithExperimentAdapter(client, dataset_adapter), enabled=True)
    except Exception:
        logger.warning("langsmith_mode=sdk but the LangSmith client could not be constructed; falling back to no-op", exc_info=True)
        return LangSmithClientAdapter(enabled=False)


class Container:
    def __init__(self, settings: Settings | None = None) -> None:
        settings = settings or get_settings()
        adapters = _build_memory_adapters() if settings.evaluation_persistence == "memory" else _build_postgres_adapters(settings)

        self.clock = SystemClockAdapter()
        self.dataset_repository = adapters.dataset_repository
        self.test_case_repository = adapters.test_case_repository
        self.run_repository = adapters.run_repository
        self.score_repository = adapters.score_repository
        self.case_execution_result_repository = adapters.case_execution_result_repository
        self.case_execution_queue_repository = adapters.case_execution_queue_repository
        self.langsmith_link_repository = adapters.langsmith_link_repository
        self.judge_bundle_status_repository = adapters.judge_bundle_status_repository
        self.regression_report_repository = adapters.regression_report_repository
        self.candidate_repository = adapters.candidate_repository
        self.gate_policy_repository = adapters.gate_policy_repository
        self.outbox_repository = adapters.outbox_repository
        self.processed_event_repository = adapters.processed_event_repository
        self.command_idempotency_repository = adapters.command_idempotency_repository
        self.audit_record_repository = adapters.audit_record_repository
        self.online_sample_repository = adapters.online_sample_repository
        self.poison_event_repository = adapters.poison_event_repository

        self.authorization_port = StaticAuthorizationPolicyAdapter()
        self.agent_runtime_port = _build_agent_runtime_port(settings)
        self.policy_approval_port = _build_policy_approval_port(settings)
        self.telemetry_artifact_port = InMemoryTelemetryArtifactAdapter()
        self.langsmith_port = _build_langsmith_port(settings)
        self.grader_registry = GraderRegistry(
            quality_judge=_build_quality_judge(settings), judge_bundle_status_repository=self.judge_bundle_status_repository,
        )
        self.online_sample_judge = _build_online_sample_judge(settings)
        self.event_publisher_port = LoggingEventPublisherAdapter()
        self.telemetry = EvaluationTelemetry()

        self.create_dataset_service = CreateDatasetService(
            self.dataset_repository, self.test_case_repository, self.audit_record_repository, self.clock,
        )
        self.publish_dataset_service = PublishDatasetService(
            self.dataset_repository, self.test_case_repository, self.audit_record_repository, self.clock,
        )
        self.create_run_service = CreateRunService(
            self.dataset_repository, self.test_case_repository, self.run_repository, self.score_repository,
            self.case_execution_queue_repository, self.langsmith_port, self.langsmith_link_repository, self.outbox_repository,
            self.audit_record_repository, self.clock, self.authorization_port,
        )
        self.execute_case_service = ExecuteCaseService(
            self.run_repository, self.test_case_repository, self.case_execution_result_repository, self.agent_runtime_port,
        )
        self.case_runner_service = CaseRunnerService(
            self.case_execution_queue_repository, self.case_execution_result_repository, self.execute_case_service,
            self.clock, lease_seconds=settings.case_runner_lease_seconds,
        )
        self.score_run_service = ScoreRunService(
            self.run_repository, self.test_case_repository, self.score_repository, self.case_execution_result_repository,
            self.grader_registry, self.telemetry_artifact_port, self.audit_record_repository, self.clock, self.telemetry,
        )
        self.compare_regression_service = CompareRegressionService(
            self.run_repository, self.test_case_repository, self.score_repository, self.case_execution_result_repository,
            self.regression_report_repository, self.outbox_repository, self.clock, self.telemetry,
        )
        self.evaluate_release_gate_service = EvaluateReleaseGateService(
            self.run_repository, self.score_repository, self.regression_report_repository, self.gate_policy_repository,
            self.langsmith_link_repository, self.outbox_repository, self.audit_record_repository, self.clock, self.telemetry,
        )
        self.create_improvement_candidate_service = CreateImprovementCandidateService(
            self.candidate_repository, self.run_repository, self.command_idempotency_repository, self.policy_approval_port,
            self.outbox_repository, self.audit_record_repository, self.clock, self.telemetry,
        )
        self.manage_canary_service = ManageCanaryService(
            self.candidate_repository, self.command_idempotency_repository, self.outbox_repository,
            self.audit_record_repository, self.clock, self.telemetry,
        )
        self.dispatch_outbox_events_service = DispatchOutboxEventsService(
            self.outbox_repository, self.event_publisher_port, self.clock,
        )
        self.evaluate_judge_calibration_service = EvaluateJudgeCalibrationService(
            self.judge_bundle_status_repository, self.clock, self.telemetry,
        )
        self.ci_evaluation_gate_service = CiEvaluationGateService(
            self.create_run_service, self.case_runner_service, self.score_run_service, self.compare_regression_service,
            self.evaluate_release_gate_service, self.compare_regression_service, self.test_case_repository,
            self.case_execution_result_repository,
        )
        self.audit_record_query_service = AuditRecordQueryService(self.audit_record_repository)
        self.grader_catalog_service = GraderCatalogService(self.grader_registry)
        self.cluster_run_failures_service = ClusterRunFailuresService(self.run_repository, self.score_repository)
        self.collect_online_sample_service = CollectOnlineSampleService(
            self.online_sample_repository, self.online_sample_judge, self.audit_record_repository, self.clock, self.telemetry,
        )
        self.evaluate_canary_promotion_service = EvaluateCanaryPromotionService(self.candidate_repository, self.online_sample_repository)
        self.consume_cross_domain_event_service = ConsumeCrossDomainEventService(
            self.processed_event_repository, self.collect_online_sample_service, self.clock,
        )
        self.consume_approval_decision_event_service = ConsumeApprovalDecisionEventService(
            self.candidate_repository, self.create_improvement_candidate_service, self.processed_event_repository,
            self.poison_event_repository, self.clock,
        )
        self.poison_event_query_service = PoisonEventQueryService(self.poison_event_repository)
        self.admin_recovery_service = AdminRecoveryService(self.dispatch_outbox_events_service, self.audit_record_repository, self.clock)

        self.create_dataset_port: CreateDatasetUseCase = self.create_dataset_service
        self.dataset_query_port: DatasetQueryUseCase = self.create_dataset_service
        self.publish_dataset_port: PublishDatasetUseCase = self.publish_dataset_service
        self.create_run_port: CreateRunUseCase = self.create_run_service
        self.run_query_port: RunQueryUseCase = self.create_run_service
        self.execute_case_port: ExecuteCaseUseCase = self.execute_case_service
        self.score_run_port: ScoreRunUseCase = self.score_run_service
        self.compare_regression_port: CompareRegressionUseCase = self.compare_regression_service
        self.report_query_port: ReportQueryUseCase = self.compare_regression_service
        self.evaluate_release_gate_port: EvaluateReleaseGateUseCase = self.evaluate_release_gate_service
        self.gate_policy_port: GatePolicyUseCase = self.evaluate_release_gate_service
        self.create_improvement_candidate_port: CreateImprovementCandidateUseCase = self.create_improvement_candidate_service
        self.candidate_query_port: CandidateQueryUseCase = self.create_improvement_candidate_service
        self.manage_canary_port: ManageCanaryUseCase = self.manage_canary_service
        self.outbox_dispatch_port: OutboxDispatchPort = self.dispatch_outbox_events_service
        self.audit_query_port: AuditQueryUseCase = self.audit_record_query_service
        self.grader_catalog_port: GraderCatalogUseCase = self.grader_catalog_service
        self.case_runner_port: CaseRunnerPort = self.case_runner_service
        self.failure_cluster_query_port: FailureClusterQueryUseCase = self.cluster_run_failures_service
        self.online_sample_port: OnlineSampleUseCase = self.collect_online_sample_service
        self.online_sample_scoring_port: OnlineSampleScoringPort = self.collect_online_sample_service
        self.canary_promotion_port: CanaryPromotionUseCase = self.evaluate_canary_promotion_service
        self.cross_domain_event_consumer_port: CrossDomainEventConsumerPort = self.consume_cross_domain_event_service
        self.approval_decision_event_consumer_port: ApprovalDecisionEventConsumerPort = self.consume_approval_decision_event_service
        self.poison_event_query_port: PoisonEventQueryUseCase = self.poison_event_query_service
        self.admin_recovery_port: AdminRecoveryUseCase = self.admin_recovery_service

        self.settings = settings


@lru_cache(maxsize=1)
def get_container() -> Container:
    return Container()


def get_create_dataset_port() -> CreateDatasetUseCase:
    return get_container().create_dataset_port


def get_dataset_query_port() -> DatasetQueryUseCase:
    return get_container().dataset_query_port


def get_publish_dataset_port() -> PublishDatasetUseCase:
    return get_container().publish_dataset_port


def get_create_run_port() -> CreateRunUseCase:
    return get_container().create_run_port


def get_run_query_port() -> RunQueryUseCase:
    return get_container().run_query_port


def get_execute_case_port() -> ExecuteCaseUseCase:
    return get_container().execute_case_port


def get_score_run_port() -> ScoreRunUseCase:
    return get_container().score_run_port


def get_compare_regression_port() -> CompareRegressionUseCase:
    return get_container().compare_regression_port


def get_report_query_port() -> ReportQueryUseCase:
    return get_container().report_query_port


def get_evaluate_release_gate_port() -> EvaluateReleaseGateUseCase:
    return get_container().evaluate_release_gate_port


def get_gate_policy_port() -> GatePolicyUseCase:
    return get_container().gate_policy_port


def get_create_improvement_candidate_port() -> CreateImprovementCandidateUseCase:
    return get_container().create_improvement_candidate_port


def get_candidate_query_port() -> CandidateQueryUseCase:
    return get_container().candidate_query_port


def get_manage_canary_port() -> ManageCanaryUseCase:
    return get_container().manage_canary_port


def get_outbox_dispatch_port() -> OutboxDispatchPort:
    return get_container().outbox_dispatch_port


def get_audit_query_port() -> AuditQueryUseCase:
    return get_container().audit_query_port


def get_grader_catalog_port() -> GraderCatalogUseCase:
    return get_container().grader_catalog_port


def get_case_runner_port() -> CaseRunnerPort:
    return get_container().case_runner_port


def get_failure_cluster_query_port() -> FailureClusterQueryUseCase:
    return get_container().failure_cluster_query_port


def get_online_sample_port() -> OnlineSampleUseCase:
    return get_container().online_sample_port


def get_canary_promotion_port() -> CanaryPromotionUseCase:
    return get_container().canary_promotion_port


def get_cross_domain_event_consumer_port() -> CrossDomainEventConsumerPort:
    return get_container().cross_domain_event_consumer_port


def get_approval_decision_event_consumer_port() -> ApprovalDecisionEventConsumerPort:
    return get_container().approval_decision_event_consumer_port


def get_poison_event_query_port() -> PoisonEventQueryUseCase:
    return get_container().poison_event_query_port


def get_admin_recovery_port() -> AdminRecoveryUseCase:
    return get_container().admin_recovery_port


def get_authorization_port():  # noqa: ANN201
    """Not a ports_in use case — the interfaces layer's own RBAC-gate dependency
    (interfaces.rest.router._require_role) reaches AuthorizationPort through this
    accessor rather than touching `get_container()` ad hoc, mirroring every other
    port accessor in this module.
    """
    return get_container().authorization_port
