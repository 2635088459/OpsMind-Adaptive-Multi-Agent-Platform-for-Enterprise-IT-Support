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

from functools import lru_cache

from evaluationimprovement.application.ports_in import (
    AuditQueryUseCase,
    CandidateQueryUseCase,
    CompareRegressionUseCase,
    CreateDatasetUseCase,
    CreateImprovementCandidateUseCase,
    CreateRunUseCase,
    DatasetQueryUseCase,
    EvaluateReleaseGateUseCase,
    ExecuteCaseUseCase,
    GatePolicyUseCase,
    GraderCatalogUseCase,
    ManageCanaryUseCase,
    OutboxDispatchPort,
    PublishDatasetUseCase,
    ReportQueryUseCase,
    RunQueryUseCase,
    ScoreRunUseCase,
)
from evaluationimprovement.application.services.audit_query import AuditRecordQueryService
from evaluationimprovement.application.services.compare_regression import CompareRegressionService
from evaluationimprovement.application.services.create_dataset import CreateDatasetService
from evaluationimprovement.application.services.create_improvement_candidate import CreateImprovementCandidateService
from evaluationimprovement.application.services.create_run import CreateRunService
from evaluationimprovement.application.services.dispatch_outbox_events import DispatchOutboxEventsService
from evaluationimprovement.application.services.evaluate_release_gate import EvaluateReleaseGateService
from evaluationimprovement.application.services.execute_case import ExecuteCaseService
from evaluationimprovement.application.services.grader_catalog import GraderCatalogService
from evaluationimprovement.application.services.manage_canary import ManageCanaryService
from evaluationimprovement.application.services.publish_dataset import PublishDatasetService
from evaluationimprovement.application.services.score_run import ScoreRunService
from evaluationimprovement.application.telemetry import EvaluationTelemetry
from evaluationimprovement.infrastructure.clock import SystemClockAdapter
from evaluationimprovement.infrastructure.event_publisher import LoggingEventPublisherAdapter
from evaluationimprovement.infrastructure.graders.registry import GraderRegistry
from evaluationimprovement.infrastructure.langsmith.client import LangSmithClientAdapter
from evaluationimprovement.infrastructure.persistence.in_memory import (
    InMemoryAuditRecordRepository,
    InMemoryCaseExecutionResultRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryDatasetRepository,
    InMemoryEvaluationRunRepository,
    InMemoryGatePolicyRepository,
    InMemoryImprovementCandidateRepository,
    InMemoryOutboxRepository,
    InMemoryProcessedEventRepository,
    InMemoryRegressionReportRepository,
    InMemoryScoreRepository,
    InMemoryTestCaseRepository,
)
from evaluationimprovement.infrastructure.persistence.postgres.repositories import (
    PostgresAuditRecordRepository,
    PostgresCaseExecutionResultRepository,
    PostgresCommandIdempotencyRepository,
    PostgresDatasetRepository,
    PostgresEvaluationRunRepository,
    PostgresGatePolicyRepository,
    PostgresImprovementCandidateRepository,
    PostgresOutboxRepository,
    PostgresProcessedEventRepository,
    PostgresRegressionReportRepository,
    PostgresScoreRepository,
    PostgresTestCaseRepository,
)
from evaluationimprovement.infrastructure.persistence.postgres.session import build_engine, build_session_factory
from evaluationimprovement.infrastructure.policy.policy_approval_client import FakePolicyApprovalAdapter
from evaluationimprovement.infrastructure.runtime.agent_runtime_client import FakeAgentRuntimeEvaluationAdapter
from evaluationimprovement.infrastructure.security.authorization import StaticAuthorizationPolicyAdapter
from evaluationimprovement.infrastructure.telemetry.artifact_store import InMemoryTelemetryArtifactAdapter
from evaluationimprovement.settings import Settings, get_settings


class _AggregateAdapters:
    """Groups every persistence.postgres-eligible repository so Container.__init__
    stays a flat, readable list of services regardless of which backend built them.
    """

    def __init__(
        self, dataset_repository, test_case_repository, run_repository, score_repository,
        case_execution_result_repository, regression_report_repository, candidate_repository, gate_policy_repository,
        outbox_repository, processed_event_repository, command_idempotency_repository, audit_record_repository,
    ) -> None:
        self.dataset_repository = dataset_repository
        self.test_case_repository = test_case_repository
        self.run_repository = run_repository
        self.score_repository = score_repository
        self.case_execution_result_repository = case_execution_result_repository
        self.regression_report_repository = regression_report_repository
        self.candidate_repository = candidate_repository
        self.gate_policy_repository = gate_policy_repository
        self.outbox_repository = outbox_repository
        self.processed_event_repository = processed_event_repository
        self.command_idempotency_repository = command_idempotency_repository
        self.audit_record_repository = audit_record_repository


def _build_memory_adapters() -> _AggregateAdapters:
    return _AggregateAdapters(
        dataset_repository=InMemoryDatasetRepository(), test_case_repository=InMemoryTestCaseRepository(),
        run_repository=InMemoryEvaluationRunRepository(), score_repository=InMemoryScoreRepository(),
        case_execution_result_repository=InMemoryCaseExecutionResultRepository(),
        regression_report_repository=InMemoryRegressionReportRepository(),
        candidate_repository=InMemoryImprovementCandidateRepository(), gate_policy_repository=InMemoryGatePolicyRepository(),
        outbox_repository=InMemoryOutboxRepository(), processed_event_repository=InMemoryProcessedEventRepository(),
        command_idempotency_repository=InMemoryCommandIdempotencyRepository(), audit_record_repository=InMemoryAuditRecordRepository(),
    )


def _build_postgres_adapters(settings: Settings) -> _AggregateAdapters:
    engine = build_engine(settings.sqlalchemy_url)
    session_factory = build_session_factory(engine)
    return _AggregateAdapters(
        dataset_repository=PostgresDatasetRepository(session_factory), test_case_repository=PostgresTestCaseRepository(session_factory),
        run_repository=PostgresEvaluationRunRepository(session_factory), score_repository=PostgresScoreRepository(session_factory),
        case_execution_result_repository=PostgresCaseExecutionResultRepository(session_factory),
        regression_report_repository=PostgresRegressionReportRepository(session_factory),
        candidate_repository=PostgresImprovementCandidateRepository(session_factory),
        gate_policy_repository=PostgresGatePolicyRepository(session_factory),
        outbox_repository=PostgresOutboxRepository(session_factory), processed_event_repository=PostgresProcessedEventRepository(session_factory),
        command_idempotency_repository=PostgresCommandIdempotencyRepository(session_factory),
        audit_record_repository=PostgresAuditRecordRepository(session_factory),
    )


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
        self.regression_report_repository = adapters.regression_report_repository
        self.candidate_repository = adapters.candidate_repository
        self.gate_policy_repository = adapters.gate_policy_repository
        self.outbox_repository = adapters.outbox_repository
        self.processed_event_repository = adapters.processed_event_repository
        self.command_idempotency_repository = adapters.command_idempotency_repository
        self.audit_record_repository = adapters.audit_record_repository

        self.authorization_port = StaticAuthorizationPolicyAdapter()
        self.agent_runtime_port = FakeAgentRuntimeEvaluationAdapter()
        self.policy_approval_port = FakePolicyApprovalAdapter()
        self.telemetry_artifact_port = InMemoryTelemetryArtifactAdapter()
        self.langsmith_port = LangSmithClientAdapter()
        self.grader_registry = GraderRegistry()
        self.event_publisher_port = LoggingEventPublisherAdapter()
        self.telemetry = EvaluationTelemetry()

        self.create_dataset_service = CreateDatasetService(
            self.dataset_repository, self.test_case_repository, self.audit_record_repository, self.clock,
        )
        self.publish_dataset_service = PublishDatasetService(
            self.dataset_repository, self.test_case_repository, self.audit_record_repository, self.clock,
        )
        self.create_run_service = CreateRunService(
            self.dataset_repository, self.run_repository, self.score_repository, self.langsmith_port, self.outbox_repository,
            self.audit_record_repository, self.clock,
        )
        self.execute_case_service = ExecuteCaseService(
            self.run_repository, self.test_case_repository, self.case_execution_result_repository, self.agent_runtime_port,
        )
        self.score_run_service = ScoreRunService(
            self.run_repository, self.test_case_repository, self.score_repository, self.case_execution_result_repository,
            self.grader_registry, self.telemetry_artifact_port, self.audit_record_repository, self.clock, self.telemetry,
        )
        self.compare_regression_service = CompareRegressionService(
            self.run_repository, self.test_case_repository, self.score_repository, self.case_execution_result_repository,
            self.regression_report_repository, self.outbox_repository, self.clock,
        )
        self.evaluate_release_gate_service = EvaluateReleaseGateService(
            self.run_repository, self.score_repository, self.regression_report_repository, self.gate_policy_repository,
            self.outbox_repository, self.audit_record_repository, self.clock, self.telemetry,
        )
        self.create_improvement_candidate_service = CreateImprovementCandidateService(
            self.candidate_repository, self.command_idempotency_repository, self.policy_approval_port, self.outbox_repository,
            self.audit_record_repository, self.clock, self.telemetry,
        )
        self.manage_canary_service = ManageCanaryService(
            self.candidate_repository, self.command_idempotency_repository, self.outbox_repository,
            self.audit_record_repository, self.clock, self.telemetry,
        )
        self.dispatch_outbox_events_service = DispatchOutboxEventsService(
            self.outbox_repository, self.event_publisher_port, self.clock,
        )
        self.audit_record_query_service = AuditRecordQueryService(self.audit_record_repository)
        self.grader_catalog_service = GraderCatalogService(self.grader_registry)

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


def get_authorization_port():  # noqa: ANN201
    """Not a ports_in use case — the interfaces layer's own RBAC-gate dependency
    (interfaces.rest.router._require_role) reaches AuthorizationPort through this
    accessor rather than touching `get_container()` ad hoc, mirroring every other
    port accessor in this module.
    """
    return get_container().authorization_port
