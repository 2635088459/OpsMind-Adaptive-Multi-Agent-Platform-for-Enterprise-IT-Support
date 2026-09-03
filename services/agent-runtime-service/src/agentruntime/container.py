"""Composition root. FastAPI has no classpath-scanning DI container the way Spring
does (13-package-and-class-design's Java siblings wire @Service/@Repository beans
implicitly); this module is the one place that is allowed to import both
agentruntime.infrastructure and agentruntime.application, construct the singleton
graph, and hand out agentruntime.application.ports_in implementations to the
interfaces layer via FastAPI's Depends(). No business rule lives here — it is pure
wiring, analogous to a Spring @Configuration class.

SPEC-ARO-002 introduces the real Postgres-backed adapters
(infrastructure.persistence.postgres) alongside SPEC-ARO-001's in-memory ones.
agentruntime.settings.Settings.agent_runtime_persistence picks between them —
"postgres" (the default, for real runs) or "memory" (fast, hermetic tests set
this explicitly; unit tests that construct services directly, bypassing this
container entirely, are unaffected either way).
"""

from __future__ import annotations

import logging
from functools import lru_cache

from agentruntime.application.ports_in import (
    AgentTaskCommandPort,
    AgentTaskQueryPort,
    AuditRecordQueryPort,
    ConversationCommandPort,
    ConversationQueryPort,
    LeaseRecoveryPort,
    OutboxDispatchPort,
    PoisonEventCommandPort,
    PoisonEventQueryPort,
    RecoveryPort,
    RuntimeEventConsumerPort,
    TicketCreatedConsumerPort,
    TicketCycleConsumerPort,
    ToolDispatchPort,
    WorkflowCommandPort,
    WorkflowLifecyclePort,
    WorkflowQueryPort,
)
from agentruntime.application.ports_out import (
    AgentTaskRepository,
    AuditRecordRepository,
    CheckpointRepository,
    CommandIdempotencyRepository,
    ConversationReasoningPort,
    OutboxRepository,
    PoisonEventRepository,
    ProcessedEventRepository,
    ToolRequestRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.services.action_confirmation import (
    ActionConfirmationService,
)
from agentruntime.application.services.agent_task_command import AgentTaskCommandService
from agentruntime.application.services.agent_task_query import AgentTaskQueryService
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.audit_query import AuditRecordQueryService
from agentruntime.application.services.cancel_workflow import CancelWorkflowService
from agentruntime.application.services.claim_agent_task import ClaimAgentTaskService
from agentruntime.application.services.complete_agent_task import (
    CompleteAgentTaskService,
)
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.consume_approval import ConsumeApprovalService
from agentruntime.application.services.consume_runtime_event import (
    ConsumeRuntimeEventService,
)
from agentruntime.application.services.consume_ticket_created import (
    ConsumeTicketCreatedService,
)
from agentruntime.application.services.consume_ticket_cycle_event import (
    ConsumeTicketCycleEventService,
)
from agentruntime.application.services.consume_tool_result import (
    ConsumeToolResultService,
)
from agentruntime.application.services.consume_verification import (
    ConsumeVerificationService,
)
from agentruntime.application.services.conversation_command import (
    ConversationCommandService,
)
from agentruntime.application.services.conversation_query import (
    ConversationQueryService,
)
from agentruntime.application.services.coordinate_agent_tasks import (
    CoordinateAgentTasksService,
)
from agentruntime.application.services.dispatch_outbox_events import (
    DispatchOutboxEventsService,
)
from agentruntime.application.services.dispatch_tool_requests import (
    DispatchToolRequestsService,
)
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.application.services.pause_workflow import PauseWorkflowService
from agentruntime.application.services.poison_event_query import PoisonEventQueryService
from agentruntime.application.services.recover_expired_lease_tasks import (
    RecoverExpiredLeaseTasksService,
)
from agentruntime.application.services.recover_workflow import RecoverWorkflowService
from agentruntime.application.services.request_tool import RequestToolService
from agentruntime.application.services.resume_workflow import ResumeWorkflowService
from agentruntime.application.services.send_message import SendMessageService
from agentruntime.application.services.start_conversation import (
    StartConversationService,
)
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.application.services.workflow_command import WorkflowCommandService
from agentruntime.application.services.workflow_lifecycle import (
    WorkflowLifecycleService,
)
from agentruntime.application.services.workflow_query import WorkflowQueryService
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.infrastructure.attachment_client import HttpAttachmentClient
from agentruntime.infrastructure.capability_policy import StaticCapabilityPolicyAdapter
from agentruntime.infrastructure.clock import SystemClockAdapter
from agentruntime.infrastructure.conversation_reasoning import (
    StaticConversationReasoningAdapter,
)
from agentruntime.infrastructure.event_publisher import LoggingEventPublisherAdapter
from agentruntime.infrastructure.event_publisher_rabbitmq import (
    RabbitMqEventPublisherAdapter,
)
from agentruntime.infrastructure.governance_approval_client import (
    HttpGovernanceApprovalClient,
)
from agentruntime.infrastructure.knowledge_retrieval_client import (
    HttpKnowledgeRetrievalClient,
)
from agentruntime.infrastructure.outbound_identity import (
    KeycloakOutboundServiceTokenProvider,
)
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryAuditRecordRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryPoisonEventRepository,
    InMemoryProcessedEventRepository,
    InMemoryToolRequestRepository,
    InMemoryWorkflowInstanceRepository,
)
from agentruntime.infrastructure.persistence.postgres.repositories import (
    PostgresAgentTaskRepository,
    PostgresAuditRecordRepository,
    PostgresCheckpointRepository,
    PostgresCommandIdempotencyRepository,
    PostgresOutboxRepository,
    PostgresPoisonEventRepository,
    PostgresProcessedEventRepository,
    PostgresToolRequestRepository,
    PostgresWorkflowInstanceRepository,
)
from agentruntime.infrastructure.persistence.postgres.session import (
    build_engine,
    build_session_factory,
)
from agentruntime.infrastructure.ticket_snapshot import NoOpTicketSnapshotPort
from agentruntime.infrastructure.ticket_workflow_client import HttpTicketWorkflowClient
from agentruntime.infrastructure.tool_gateway import LoggingToolGatewayPort
from agentruntime.infrastructure.workflow_definition_catalog import (
    StaticWorkflowDefinitionCatalogAdapter,
)
from agentruntime.settings import Settings, get_settings


class _PersistenceAdapters:
    """Groups the eight ports_out repositories so Container.__init__ stays a flat,
    readable list of services regardless of which backend built them.
    """

    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        agent_task_repository: AgentTaskRepository,
        checkpoint_repository: CheckpointRepository,
        tool_request_repository: ToolRequestRepository,
        processed_event_repository: ProcessedEventRepository,
        outbox_repository: OutboxRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        poison_event_repository: PoisonEventRepository,
        audit_record_repository: AuditRecordRepository,
    ) -> None:
        self.workflow_instance_repository = workflow_instance_repository
        self.agent_task_repository = agent_task_repository
        self.checkpoint_repository = checkpoint_repository
        self.tool_request_repository = tool_request_repository
        self.processed_event_repository = processed_event_repository
        self.outbox_repository = outbox_repository
        self.command_idempotency_repository = command_idempotency_repository
        self.poison_event_repository = poison_event_repository
        self.audit_record_repository = audit_record_repository


def _build_memory_adapters() -> _PersistenceAdapters:
    return _PersistenceAdapters(
        workflow_instance_repository=InMemoryWorkflowInstanceRepository(),
        agent_task_repository=InMemoryAgentTaskRepository(),
        checkpoint_repository=InMemoryCheckpointRepository(),
        tool_request_repository=InMemoryToolRequestRepository(),
        processed_event_repository=InMemoryProcessedEventRepository(),
        outbox_repository=InMemoryOutboxRepository(),
        command_idempotency_repository=InMemoryCommandIdempotencyRepository(),
        poison_event_repository=InMemoryPoisonEventRepository(),
        audit_record_repository=InMemoryAuditRecordRepository(),
    )


logger = logging.getLogger("agentruntime.container")


def _build_conversation_reasoning_port(settings: Settings) -> ConversationReasoningPort:
    """Settings.conversation_reasoning_mode="static" (default) keeps
    StaticConversationReasoningAdapter — every hermetic test in this service relies on
    it. "anthropic"/"openai" each wire their own real adapter — same import-lazily/
    degrade-to-safe-default shape evaluation-improvement-service's own
    `_build_quality_judge()` already established in this platform for the anthropic
    SDK; the openai branch mirrors it exactly for this service's own first use of that
    SDK.
    """

    if settings.conversation_reasoning_mode == "anthropic":
        try:
            import anthropic

            from agentruntime.infrastructure.conversation_reasoning import AnthropicConversationReasoningAdapter

            client = anthropic.Anthropic(api_key=settings.anthropic_api_key, timeout=settings.conversation_reasoning_timeout_seconds) if settings.anthropic_api_key else anthropic.Anthropic(timeout=settings.conversation_reasoning_timeout_seconds)
            return AnthropicConversationReasoningAdapter(client, settings.conversation_reasoning_anthropic_model)
        except Exception:
            logger.warning(
                "conversation_reasoning_mode=anthropic but the Anthropic client could not be constructed; falling back to the static placeholder",
                exc_info=True,
            )
            return StaticConversationReasoningAdapter()

    if settings.conversation_reasoning_mode == "openai":
        try:
            import openai

            from agentruntime.infrastructure.conversation_reasoning import OpenAIConversationReasoningAdapter

            client = openai.OpenAI(api_key=settings.openai_api_key, timeout=settings.conversation_reasoning_timeout_seconds) if settings.openai_api_key else openai.OpenAI(timeout=settings.conversation_reasoning_timeout_seconds)
            return OpenAIConversationReasoningAdapter(client, settings.conversation_reasoning_openai_model)
        except Exception:
            logger.warning(
                "conversation_reasoning_mode=openai but the OpenAI client could not be constructed; falling back to the static placeholder",
                exc_info=True,
            )
            return StaticConversationReasoningAdapter()

    return StaticConversationReasoningAdapter()


def _build_postgres_adapters(settings: Settings) -> _PersistenceAdapters:
    engine = build_engine(settings.sqlalchemy_url)
    session_factory = build_session_factory(engine)
    return _PersistenceAdapters(
        workflow_instance_repository=PostgresWorkflowInstanceRepository(session_factory),
        agent_task_repository=PostgresAgentTaskRepository(session_factory),
        checkpoint_repository=PostgresCheckpointRepository(session_factory),
        tool_request_repository=PostgresToolRequestRepository(session_factory),
        processed_event_repository=PostgresProcessedEventRepository(session_factory),
        outbox_repository=PostgresOutboxRepository(session_factory),
        command_idempotency_repository=PostgresCommandIdempotencyRepository(session_factory),
        poison_event_repository=PostgresPoisonEventRepository(session_factory),
        audit_record_repository=PostgresAuditRecordRepository(session_factory),
    )


class Container:
    def __init__(self, settings: Settings | None = None) -> None:
        settings = settings or get_settings()
        adapters = _build_memory_adapters() if settings.agent_runtime_persistence == "memory" else _build_postgres_adapters(settings)

        self.clock = SystemClockAdapter()

        self.workflow_instance_repository = adapters.workflow_instance_repository
        self.agent_task_repository = adapters.agent_task_repository
        self.checkpoint_repository = adapters.checkpoint_repository
        self.tool_request_repository = adapters.tool_request_repository
        self.processed_event_repository = adapters.processed_event_repository
        self.outbox_repository = adapters.outbox_repository
        self.command_idempotency_repository = adapters.command_idempotency_repository
        self.poison_event_repository = adapters.poison_event_repository
        self.audit_record_repository = adapters.audit_record_repository

        self.tool_gateway_port = LoggingToolGatewayPort(self.clock)
        self.ticket_snapshot_port = NoOpTicketSnapshotPort()
        # SPEC-ARO-043 (phase-10 Conversational Intake): this service's own outbound
        # service identity — used by outbound calls that are genuinely
        # service-to-service (SPEC-ARO-041's future triage-as-automation-agent call),
        # never by SPEC-ARO-038's create_ticket() (see TicketWorkflowClientPort's own
        # docstring for why).
        self.outbound_service_token_provider = KeycloakOutboundServiceTokenProvider(
            settings.keycloak_token_url, settings.agent_runtime_service_client_id, settings.agent_runtime_service_client_secret,
        )
        self.ticket_workflow_client = HttpTicketWorkflowClient(
            settings.ticket_workflow_base_url, token_provider=self.outbound_service_token_provider,
        )
        self.knowledge_retrieval_client = HttpKnowledgeRetrievalClient(settings.memory_knowledge_base_url)
        self.conversation_reasoning_port = _build_conversation_reasoning_port(settings)
        # SPEC-ARO-039's own multimodal follow-up. Same outbound service identity as
        # ticket_workflow_client's own triage_ticket() call — see AttachmentClientPort's
        # own docstring for why.
        self.attachment_client = HttpAttachmentClient(
            settings.attachment_service_base_url, token_provider=self.outbound_service_token_provider,
        )
        self.governance_approval_client = HttpGovernanceApprovalClient(
            settings.policy_approval_governance_base_url, token_provider=self.outbound_service_token_provider,
        )
        self.event_publisher_port = (
            RabbitMqEventPublisherAdapter(settings) if settings.event_publisher_adapter == "rabbitmq" else LoggingEventPublisherAdapter()
        )
        self.workflow_definition_catalog_port = StaticWorkflowDefinitionCatalogAdapter()
        self.capability_policy_port = StaticCapabilityPolicyAdapter()

        # SPEC-ARO-034 12-observability: one RuntimeTelemetry (metrics) and one
        # AuditRecorder (persisted audit trail), injected everywhere they're needed —
        # mirrors the sibling ticket-workflow-service's own TicketTelemetry/
        # AuditRecordPort being wired once at the composition root, not re-instantiated
        # per service.
        self.telemetry = RuntimeTelemetry()
        self.audit_recorder = AuditRecorder(self.audit_record_repository, self.clock)

        self.coordinate_agent_tasks_service = CoordinateAgentTasksService(self.agent_task_repository, self.checkpoint_repository)

        self.start_workflow_service = StartWorkflowService(
            self.workflow_instance_repository, self.checkpoint_repository, self.outbox_repository,
            self.command_idempotency_repository, self.clock, self.coordinate_agent_tasks_service,
            self.telemetry, self.audit_recorder,
        )
        self.pause_workflow_service = PauseWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock,
            self.checkpoint_repository, self.telemetry, self.audit_recorder,
        )
        self.resume_workflow_service = ResumeWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock,
            self.checkpoint_repository, self.audit_recorder,
        )
        self.claim_agent_task_service = ClaimAgentTaskService(
            self.agent_task_repository, self.workflow_instance_repository, self.clock, self.telemetry, self.audit_recorder,
        )
        self.complete_workflow_service = CompleteWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock,
            self.checkpoint_repository, self.telemetry, self.audit_recorder,
        )
        self.fail_workflow_service = FailWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock,
            self.checkpoint_repository, self.telemetry, self.audit_recorder,
        )
        self.complete_agent_task_service = CompleteAgentTaskService(
            self.agent_task_repository, self.workflow_instance_repository, self.checkpoint_repository, self.outbox_repository,
            self.command_idempotency_repository, self.clock, self.coordinate_agent_tasks_service,
            self.complete_workflow_service, self.fail_workflow_service, self.telemetry, self.audit_recorder,
        )
        self.request_tool_service = RequestToolService(
            self.checkpoint_repository, self.tool_request_repository,
            self.command_idempotency_repository, self.clock, self.workflow_instance_repository,
            self.agent_task_repository, self.capability_policy_port, self.audit_recorder,
        )
        self.dispatch_tool_requests_service = DispatchToolRequestsService(
            self.tool_request_repository, self.tool_gateway_port, self.clock
        )
        self.consume_tool_result_service = ConsumeToolResultService(
            self.tool_request_repository, self.agent_task_repository, self.workflow_instance_repository,
            self.checkpoint_repository, self.outbox_repository, self.clock, self.coordinate_agent_tasks_service,
            self.complete_workflow_service, self.fail_workflow_service,
        )
        self.consume_approval_service = ConsumeApprovalService(
            self.workflow_instance_repository, self.checkpoint_repository, self.clock, self.fail_workflow_service,
        )
        self.consume_verification_service = ConsumeVerificationService(
            self.workflow_instance_repository, self.clock, self.coordinate_agent_tasks_service,
            self.complete_workflow_service, self.fail_workflow_service,
        )
        self.consume_runtime_event_service = ConsumeRuntimeEventService(
            self.processed_event_repository, self.workflow_instance_repository, self.clock,
            self.consume_tool_result_service, self.consume_approval_service, self.consume_verification_service,
            self.poison_event_repository, self.telemetry, self.audit_recorder,
        )
        self.consume_ticket_created_service = ConsumeTicketCreatedService(
            self.processed_event_repository, self.ticket_snapshot_port, self.workflow_definition_catalog_port,
            self.start_workflow_service, self.clock, self.telemetry,
        )
        self.recover_workflow_service = RecoverWorkflowService(
            self.workflow_instance_repository, self.checkpoint_repository, self.agent_task_repository, self.clock,
            self.fail_workflow_service, self.telemetry, self.audit_recorder,
        )
        self.recover_expired_lease_tasks_service = RecoverExpiredLeaseTasksService(
            self.agent_task_repository, self.workflow_instance_repository, self.clock, self.telemetry, self.audit_recorder,
        )
        self.dispatch_outbox_events_service = DispatchOutboxEventsService(
            self.outbox_repository, self.event_publisher_port, self.clock, self.telemetry,
        )
        self.cancel_workflow_service = CancelWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock,
            self.checkpoint_repository, self.audit_recorder,
        )
        self.consume_ticket_cycle_event_service = ConsumeTicketCycleEventService(
            self.processed_event_repository, self.workflow_instance_repository, self.clock, self.cancel_workflow_service,
        )
        self.start_conversation_service = StartConversationService(
            self.ticket_workflow_client, self.start_workflow_service, self.command_idempotency_repository, self.clock,
        )
        self.send_message_service = SendMessageService(
            self.workflow_instance_repository, self.agent_task_repository, self.checkpoint_repository,
            self.command_idempotency_repository, self.clock, self.knowledge_retrieval_client, self.conversation_reasoning_port,
            self.ticket_workflow_client, self.complete_workflow_service,
            settings.escalation_default_category_id, settings.escalation_default_support_queue_id,
            settings.escalation_default_priority, settings.escalation_default_team_name,
            attachment_client_port=self.attachment_client,
        )
        self.action_confirmation_service = ActionConfirmationService(
            self.workflow_instance_repository, self.agent_task_repository, self.checkpoint_repository,
            self.tool_request_repository, self.command_idempotency_repository, self.clock, self.governance_approval_client,
            settings.confirm_bounded_wait_timeout_seconds, settings.confirm_bounded_wait_poll_interval_seconds,
        )
        self.conversation_command_service = ConversationCommandService(
            self.start_conversation_service, self.send_message_service, self.action_confirmation_service,
        )
        self.conversation_query_service = ConversationQueryService(self.workflow_instance_repository)
        self.workflow_query_service = WorkflowQueryService(self.workflow_instance_repository, self.checkpoint_repository)
        self.agent_task_query_service = AgentTaskQueryService(self.agent_task_repository)
        self.poison_event_query_service = PoisonEventQueryService(self.poison_event_repository, self.clock)
        self.audit_record_query_service = AuditRecordQueryService(self.audit_record_repository)

        self.workflow_command_port: WorkflowCommandPort = WorkflowCommandService(
            self.start_workflow_service, self.pause_workflow_service, self.resume_workflow_service
        )
        self.agent_task_command_port: AgentTaskCommandPort = AgentTaskCommandService(
            self.claim_agent_task_service, self.complete_agent_task_service, self.request_tool_service
        )
        self.runtime_event_consumer_port: RuntimeEventConsumerPort = self.consume_runtime_event_service
        self.ticket_created_consumer_port: TicketCreatedConsumerPort = self.consume_ticket_created_service
        self.ticket_cycle_consumer_port: TicketCycleConsumerPort = self.consume_ticket_cycle_event_service
        self.recovery_port: RecoveryPort = self.recover_workflow_service
        self.lease_recovery_port: LeaseRecoveryPort = self.recover_expired_lease_tasks_service
        self.outbox_dispatch_port: OutboxDispatchPort = self.dispatch_outbox_events_service
        self.tool_dispatch_port: ToolDispatchPort = self.dispatch_tool_requests_service
        self.workflow_lifecycle_port: WorkflowLifecyclePort = WorkflowLifecycleService(
            self.complete_workflow_service, self.fail_workflow_service, self.cancel_workflow_service
        )
        self.workflow_query_port: WorkflowQueryPort = self.workflow_query_service
        self.agent_task_query_port: AgentTaskQueryPort = self.agent_task_query_service
        self.poison_event_query_port: PoisonEventQueryPort = self.poison_event_query_service
        self.poison_event_command_port: PoisonEventCommandPort = self.poison_event_query_service
        self.audit_record_query_port: AuditRecordQueryPort = self.audit_record_query_service
        self.conversation_command_port: ConversationCommandPort = self.conversation_command_service
        self.conversation_query_port: ConversationQueryPort = self.conversation_query_service


@lru_cache(maxsize=1)
def get_container() -> Container:
    return Container()


def get_workflow_command_port() -> WorkflowCommandPort:
    return get_container().workflow_command_port


def get_agent_task_command_port() -> AgentTaskCommandPort:
    return get_container().agent_task_command_port


def get_runtime_event_consumer_port() -> RuntimeEventConsumerPort:
    return get_container().runtime_event_consumer_port


def get_ticket_created_consumer_port() -> TicketCreatedConsumerPort:
    return get_container().ticket_created_consumer_port


def get_ticket_cycle_consumer_port() -> TicketCycleConsumerPort:
    return get_container().ticket_cycle_consumer_port


def get_recovery_port() -> RecoveryPort:
    return get_container().recovery_port


def get_lease_recovery_port() -> LeaseRecoveryPort:
    return get_container().lease_recovery_port


def get_outbox_dispatch_port() -> OutboxDispatchPort:
    return get_container().outbox_dispatch_port


def get_tool_dispatch_port() -> ToolDispatchPort:
    return get_container().tool_dispatch_port


def get_workflow_lifecycle_port() -> WorkflowLifecyclePort:
    return get_container().workflow_lifecycle_port


def get_workflow_query_port() -> WorkflowQueryPort:
    return get_container().workflow_query_port


def get_agent_task_query_port() -> AgentTaskQueryPort:
    return get_container().agent_task_query_port


def get_poison_event_query_port() -> PoisonEventQueryPort:
    return get_container().poison_event_query_port


def get_poison_event_command_port() -> PoisonEventCommandPort:
    return get_container().poison_event_command_port


def get_audit_record_query_port() -> AuditRecordQueryPort:
    return get_container().audit_record_query_port


def get_conversation_command_port() -> ConversationCommandPort:
    return get_container().conversation_command_port


def get_conversation_query_port() -> ConversationQueryPort:
    return get_container().conversation_query_port
