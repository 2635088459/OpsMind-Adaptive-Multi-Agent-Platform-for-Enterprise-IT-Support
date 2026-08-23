"""Composition root. FastAPI has no classpath-scanning DI container the way
Spring does; this module is the one place allowed to import both
``tool_gateway.adapters`` and ``tool_gateway.application``, construct the
singleton graph, and hand out ``tool_gateway.application.ports_in``
implementations to ``tool_gateway.api``/``tool_gateway.workers`` via
``Depends()``/direct construction. No business rule lives here — pure wiring,
mirroring memory-knowledge-service's own ``container.py`` exactly.

SPEC-TG-002 introduces the real Postgres-backed adapters
(adapters.db.postgres_repositories) alongside SPEC-TG-001's in-memory ones.
``tool_gateway.settings.Settings.tool_gateway_persistence`` picks between them
— "postgres" (the default, for real runs) or "memory" (fast, hermetic tests
set this explicitly; unit tests that construct services directly, bypassing
this container entirely, are unaffected either way). SPEC-TG-003 does the same
for ``event_publisher_adapter`` ("logging" default vs "rabbitmq").
"""

from __future__ import annotations

from functools import lru_cache

from tool_gateway.adapters.approval.approval_client import InMemoryApprovalAdapter
from tool_gateway.adapters.clock import SystemClockAdapter
from tool_gateway.adapters.connectors.builtin.echo_connector import EchoConnectorAdapter
from tool_gateway.adapters.connectors.registry import ConnectorRegistry
from tool_gateway.adapters.credentials.vault_adapter import InMemoryVaultCredentialAdapter
from tool_gateway.adapters.db.repositories import (
    InMemoryAuditRecordRepository,
    InMemoryConnectorRepository,
    InMemoryCredentialBindingRepository,
    InMemoryOutboxRepository,
    InMemoryProcessedEventRepository,
    InMemoryResultEnvelopeRepository,
    InMemoryToolExecutionRepository,
    InMemoryToolRequestRepository,
)
from tool_gateway.adapters.db.postgres_repositories import (
    PostgresAuditRecordRepository,
    PostgresConnectorRepository,
    PostgresCredentialBindingRepository,
    PostgresOutboxRepository,
    PostgresProcessedEventRepository,
    PostgresResultEnvelopeRepository,
    PostgresToolExecutionRepository,
    PostgresToolRequestRepository,
)
from tool_gateway.adapters.db.session import build_engine, build_session_factory
from tool_gateway.adapters.events.logging_publisher import LoggingEventBusAdapter
from tool_gateway.adapters.events.rabbitmq_publisher import RabbitMqEventBusAdapter
from tool_gateway.adapters.policy.policy_client import StaticPolicyAdapter
from tool_gateway.adapters.raw_output.in_memory_raw_output_store import InMemoryRawOutputStoreAdapter
from tool_gateway.adapters.redaction.redaction_service import RegexRedactionAdapter
from tool_gateway.application.admin_outbox import AdminOutboxService
from tool_gateway.application.approve_tool_request import ApproveToolRequestService
from tool_gateway.application.cancel_tool_request import CancelToolRequestService
from tool_gateway.application.consume_policy_rule_changed import ConsumePolicyRuleChangedService
from tool_gateway.application.consume_workflow_cancelled import ConsumeWorkflowCancelledService
from tool_gateway.application.create_tool_request import CreateToolRequestService
from tool_gateway.application.evaluate_tool_request import EvaluateToolRequestService
from tool_gateway.application.execute_tool_request import ExecuteToolRequestService
from tool_gateway.application.gateway_recovery import GatewayRecoveryService
from tool_gateway.application.ports_in import (
    AdminOutboxUseCase,
    ApproveToolRequestUseCase,
    AuditQueryUseCase,
    CancelToolRequestUseCase,
    CreateToolRequestUseCase,
    EvaluateToolRequestUseCase,
    ExecuteToolRequestUseCase,
    GatewayRecoveryUseCase,
    PolicyRuleChangeConsumerUseCase,
    PublishOutboxUseCase,
    ReclaimExpiredLeasesUseCase,
    ReconcileExecutionUseCase,
    RegisterConnectorUseCase,
    ToolRequestQueryUseCase,
    ToolResultQueryUseCase,
    WorkflowCancelledConsumerUseCase,
)
from tool_gateway.application.publish_outbox import PublishOutboxService
from tool_gateway.application.query_audit import AuditQueryService
from tool_gateway.application.reclaim_expired_leases import ReclaimExpiredLeasesService
from tool_gateway.application.reconcile_execution import ReconcileExecutionService
from tool_gateway.application.register_connector import RegisterConnectorService
from tool_gateway.application.telemetry import ToolGatewayTelemetry
from tool_gateway.settings import Settings, get_settings


class _PersistenceAdapters:
    """Groups the eight storage_port repositories so Container.__init__ stays a
    flat, readable list of services regardless of which backend built them —
    mirrors memory-knowledge-service's own container._PersistenceAdapters.
    """

    def __init__(
        self, tool_request_repository, tool_execution_repository, result_envelope_repository, connector_repository,
        outbox_repository, processed_event_repository, audit_record_repository, credential_binding_repository,
    ) -> None:
        self.tool_request_repository = tool_request_repository
        self.tool_execution_repository = tool_execution_repository
        self.result_envelope_repository = result_envelope_repository
        self.connector_repository = connector_repository
        self.outbox_repository = outbox_repository
        self.processed_event_repository = processed_event_repository
        self.audit_record_repository = audit_record_repository
        self.credential_binding_repository = credential_binding_repository


def _build_memory_adapters() -> _PersistenceAdapters:
    return _PersistenceAdapters(
        tool_request_repository=InMemoryToolRequestRepository(), tool_execution_repository=InMemoryToolExecutionRepository(),
        result_envelope_repository=InMemoryResultEnvelopeRepository(), connector_repository=InMemoryConnectorRepository(),
        outbox_repository=InMemoryOutboxRepository(), processed_event_repository=InMemoryProcessedEventRepository(),
        audit_record_repository=InMemoryAuditRecordRepository(), credential_binding_repository=InMemoryCredentialBindingRepository(),
    )


def _build_postgres_adapters(settings: Settings) -> _PersistenceAdapters:
    engine = build_engine(settings.sqlalchemy_url)
    session_factory = build_session_factory(engine)
    return _PersistenceAdapters(
        tool_request_repository=PostgresToolRequestRepository(session_factory),
        tool_execution_repository=PostgresToolExecutionRepository(session_factory),
        result_envelope_repository=PostgresResultEnvelopeRepository(session_factory),
        connector_repository=PostgresConnectorRepository(session_factory),
        outbox_repository=PostgresOutboxRepository(session_factory),
        processed_event_repository=PostgresProcessedEventRepository(session_factory),
        audit_record_repository=PostgresAuditRecordRepository(session_factory),
        credential_binding_repository=PostgresCredentialBindingRepository(session_factory),
    )


class Container:
    def __init__(self, settings: Settings | None = None) -> None:
        settings = settings or get_settings()
        adapters = _build_memory_adapters() if settings.tool_gateway_persistence == "memory" else _build_postgres_adapters(settings)

        self.clock = SystemClockAdapter()

        self.tool_request_repository = adapters.tool_request_repository
        self.tool_execution_repository = adapters.tool_execution_repository
        self.result_envelope_repository = adapters.result_envelope_repository
        self.connector_repository = adapters.connector_repository
        self.outbox_repository = adapters.outbox_repository
        self.processed_event_repository = adapters.processed_event_repository
        self.audit_record_repository = adapters.audit_record_repository
        self.credential_binding_repository = adapters.credential_binding_repository

        self.connector_registry_port = ConnectorRegistry(self.connector_repository)
        self.default_connector_adapter = EchoConnectorAdapter()
        self.credential_port = InMemoryVaultCredentialAdapter(self.credential_binding_repository, self.clock)
        self.policy_port = StaticPolicyAdapter(self.clock)
        self.approval_port = InMemoryApprovalAdapter(self.clock)
        self.redaction_port = RegexRedactionAdapter()
        # SPEC-TG-020: in-memory placeholder — a real deployment backs this
        # with controlled object storage; see that adapter's own docstring.
        self.raw_output_store_port = InMemoryRawOutputStoreAdapter()
        # SPEC-TG-003 08-transaction-and-outbox §"Outbox Publisher": "logging"
        # stays the default for the same reason memory-knowledge-service's own
        # event_publisher_adapter default does — see settings.py's own docstring.
        self.event_bus_port = (
            RabbitMqEventBusAdapter(settings) if settings.event_publisher_adapter == "rabbitmq" else LoggingEventBusAdapter()
        )
        # SPEC-TG-026 12-observability: process-wide OTel meter/tracer state is
        # configured once, at the FastAPI app entry point (``main.create_app``
        # -> ``adapters.observability.otel_setup.configure_observability``) —
        # this singleton just names the instruments every application service
        # below shares.
        self.telemetry = ToolGatewayTelemetry()

        self.create_tool_request_service = CreateToolRequestService(
            self.tool_request_repository, self.connector_registry_port, self.outbox_repository,
            self.audit_record_repository, self.clock, self.telemetry,
        )
        self.evaluate_tool_request_service = EvaluateToolRequestService(
            self.tool_request_repository, self.policy_port, self.approval_port, self.outbox_repository,
            self.audit_record_repository, self.clock, self.telemetry,
        )
        self.approve_tool_request_service = ApproveToolRequestService(
            self.tool_request_repository, self.outbox_repository, self.processed_event_repository,
            self.audit_record_repository, self.clock, self.telemetry,
        )
        self.consume_policy_rule_changed_service = ConsumePolicyRuleChangedService(
            self.processed_event_repository, self.audit_record_repository, self.clock,
        )
        self.cancel_tool_request_service = CancelToolRequestService(
            self.tool_request_repository, self.tool_execution_repository, self.connector_registry_port,
            self.outbox_repository, self.audit_record_repository, self.clock, self.telemetry,
        )
        self.consume_workflow_cancelled_service = ConsumeWorkflowCancelledService(
            self.tool_request_repository, self.cancel_tool_request_service, self.processed_event_repository,
            self.audit_record_repository, self.clock,
        )
        self.execute_tool_request_service = ExecuteToolRequestService(
            self.tool_request_repository, self.tool_execution_repository, self.result_envelope_repository,
            self.connector_registry_port, self.credential_port, self.redaction_port, self.raw_output_store_port,
            self.outbox_repository, self.audit_record_repository, self.clock, self.telemetry,
        )
        self.reconcile_execution_service = ReconcileExecutionService(
            self.tool_execution_repository, self.tool_request_repository, self.result_envelope_repository,
            self.connector_registry_port, self.redaction_port, self.raw_output_store_port, self.outbox_repository,
            self.audit_record_repository, self.clock, self.telemetry,
        )
        self.reclaim_expired_leases_service = ReclaimExpiredLeasesService(
            self.tool_execution_repository, self.audit_record_repository, self.clock,
        )
        self.publish_outbox_service = PublishOutboxService(
            self.outbox_repository, self.event_bus_port, self.clock, self.telemetry,
        )
        self.register_connector_service = RegisterConnectorService(
            self.connector_registry_port, self.default_connector_adapter, self.outbox_repository,
            self.audit_record_repository, self.clock,
            degrade_after_failures=settings.connector_degrade_after_failures,
            disable_after_failures=settings.connector_disable_after_failures,
        )
        self.audit_query_service = AuditQueryService(self.audit_record_repository)
        self.admin_outbox_service = AdminOutboxService(self.outbox_repository, self.audit_record_repository, self.clock)
        self.gateway_recovery_service = GatewayRecoveryService(self.reclaim_expired_leases_service, self.publish_outbox_service)

        self.create_tool_request_port: CreateToolRequestUseCase = self.create_tool_request_service
        self.evaluate_tool_request_port: EvaluateToolRequestUseCase = self.evaluate_tool_request_service
        self.approve_tool_request_port: ApproveToolRequestUseCase = self.approve_tool_request_service
        self.cancel_tool_request_port: CancelToolRequestUseCase = self.cancel_tool_request_service
        self.execute_tool_request_port: ExecuteToolRequestUseCase = self.execute_tool_request_service
        self.reconcile_execution_port: ReconcileExecutionUseCase = self.reconcile_execution_service
        self.reclaim_expired_leases_port: ReclaimExpiredLeasesUseCase = self.reclaim_expired_leases_service
        self.publish_outbox_port: PublishOutboxUseCase = self.publish_outbox_service
        self.register_connector_port: RegisterConnectorUseCase = self.register_connector_service
        self.tool_request_query_port: ToolRequestQueryUseCase = self.create_tool_request_service
        self.tool_result_query_port: ToolResultQueryUseCase = self.execute_tool_request_service
        self.policy_rule_change_consumer_port: PolicyRuleChangeConsumerUseCase = self.consume_policy_rule_changed_service
        self.workflow_cancelled_consumer_port: WorkflowCancelledConsumerUseCase = self.consume_workflow_cancelled_service
        self.audit_query_port: AuditQueryUseCase = self.audit_query_service
        self.admin_outbox_port: AdminOutboxUseCase = self.admin_outbox_service
        self.gateway_recovery_port: GatewayRecoveryUseCase = self.gateway_recovery_service


@lru_cache(maxsize=1)
def get_container() -> Container:
    return Container()


def get_create_tool_request_port() -> CreateToolRequestUseCase:
    return get_container().create_tool_request_port


def get_evaluate_tool_request_port() -> EvaluateToolRequestUseCase:
    return get_container().evaluate_tool_request_port


def get_approve_tool_request_port() -> ApproveToolRequestUseCase:
    return get_container().approve_tool_request_port


def get_policy_rule_change_consumer_port() -> PolicyRuleChangeConsumerUseCase:
    return get_container().policy_rule_change_consumer_port


def get_workflow_cancelled_consumer_port() -> WorkflowCancelledConsumerUseCase:
    return get_container().workflow_cancelled_consumer_port


def get_cancel_tool_request_port() -> CancelToolRequestUseCase:
    return get_container().cancel_tool_request_port


def get_execute_tool_request_port() -> ExecuteToolRequestUseCase:
    return get_container().execute_tool_request_port


def get_reconcile_execution_port() -> ReconcileExecutionUseCase:
    return get_container().reconcile_execution_port


def get_reclaim_expired_leases_port() -> ReclaimExpiredLeasesUseCase:
    return get_container().reclaim_expired_leases_port


def get_publish_outbox_port() -> PublishOutboxUseCase:
    return get_container().publish_outbox_port


def get_register_connector_port() -> RegisterConnectorUseCase:
    return get_container().register_connector_port


def get_tool_request_query_port() -> ToolRequestQueryUseCase:
    return get_container().tool_request_query_port


def get_tool_result_query_port() -> ToolResultQueryUseCase:
    return get_container().tool_result_query_port


def get_audit_query_port() -> AuditQueryUseCase:
    return get_container().audit_query_port


def get_admin_outbox_port() -> AdminOutboxUseCase:
    return get_container().admin_outbox_port


def get_gateway_recovery_port() -> GatewayRecoveryUseCase:
    return get_container().gateway_recovery_port
