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

from functools import lru_cache

from agentruntime.application.ports_in import (
    AgentTaskCommandPort,
    AgentTaskQueryPort,
    OutboxDispatchPort,
    RecoveryPort,
    RuntimeEventConsumerPort,
    TicketCreatedConsumerPort,
    WorkflowCommandPort,
    WorkflowLifecyclePort,
    WorkflowQueryPort,
)
from agentruntime.application.ports_out import (
    AgentTaskRepository,
    CheckpointRepository,
    CommandIdempotencyRepository,
    OutboxRepository,
    ProcessedEventRepository,
    ToolRequestRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.services.agent_task_command import AgentTaskCommandService
from agentruntime.application.services.agent_task_query import AgentTaskQueryService
from agentruntime.application.services.cancel_workflow import CancelWorkflowService
from agentruntime.application.services.claim_agent_task import ClaimAgentTaskService
from agentruntime.application.services.complete_agent_task import CompleteAgentTaskService
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.consume_runtime_event import ConsumeRuntimeEventService
from agentruntime.application.services.consume_ticket_created import ConsumeTicketCreatedService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.dispatch_outbox_events import DispatchOutboxEventsService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.application.services.pause_workflow import PauseWorkflowService
from agentruntime.application.services.recover_workflow import RecoverWorkflowService
from agentruntime.application.services.request_tool import RequestToolService
from agentruntime.application.services.resume_workflow import ResumeWorkflowService
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.application.services.workflow_command import WorkflowCommandService
from agentruntime.application.services.workflow_lifecycle import WorkflowLifecycleService
from agentruntime.application.services.workflow_query import WorkflowQueryService
from agentruntime.infrastructure.clock import SystemClockAdapter
from agentruntime.infrastructure.event_publisher import LoggingEventPublisherAdapter
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryProcessedEventRepository,
    InMemoryToolRequestRepository,
    InMemoryWorkflowInstanceRepository,
)
from agentruntime.infrastructure.persistence.postgres.repositories import (
    PostgresAgentTaskRepository,
    PostgresCheckpointRepository,
    PostgresCommandIdempotencyRepository,
    PostgresOutboxRepository,
    PostgresProcessedEventRepository,
    PostgresToolRequestRepository,
    PostgresWorkflowInstanceRepository,
)
from agentruntime.infrastructure.persistence.postgres.session import build_engine, build_session_factory
from agentruntime.infrastructure.ticket_snapshot import NoOpTicketSnapshotPort
from agentruntime.infrastructure.tool_gateway import LoggingToolGatewayPort
from agentruntime.infrastructure.workflow_definition_catalog import StaticWorkflowDefinitionCatalogAdapter
from agentruntime.settings import Settings, get_settings


class _PersistenceAdapters:
    """Groups the seven ports_out repositories so Container.__init__ stays a flat,
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
    ) -> None:
        self.workflow_instance_repository = workflow_instance_repository
        self.agent_task_repository = agent_task_repository
        self.checkpoint_repository = checkpoint_repository
        self.tool_request_repository = tool_request_repository
        self.processed_event_repository = processed_event_repository
        self.outbox_repository = outbox_repository
        self.command_idempotency_repository = command_idempotency_repository


def _build_memory_adapters() -> _PersistenceAdapters:
    return _PersistenceAdapters(
        workflow_instance_repository=InMemoryWorkflowInstanceRepository(),
        agent_task_repository=InMemoryAgentTaskRepository(),
        checkpoint_repository=InMemoryCheckpointRepository(),
        tool_request_repository=InMemoryToolRequestRepository(),
        processed_event_repository=InMemoryProcessedEventRepository(),
        outbox_repository=InMemoryOutboxRepository(),
        command_idempotency_repository=InMemoryCommandIdempotencyRepository(),
    )


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

        self.tool_gateway_port = LoggingToolGatewayPort(self.clock)
        self.ticket_snapshot_port = NoOpTicketSnapshotPort()
        self.event_publisher_port = LoggingEventPublisherAdapter()
        self.workflow_definition_catalog_port = StaticWorkflowDefinitionCatalogAdapter()

        self.coordinate_agent_tasks_service = CoordinateAgentTasksService(self.agent_task_repository, self.checkpoint_repository)

        self.start_workflow_service = StartWorkflowService(
            self.workflow_instance_repository, self.checkpoint_repository, self.outbox_repository,
            self.command_idempotency_repository, self.clock, self.coordinate_agent_tasks_service,
        )
        self.pause_workflow_service = PauseWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock
        )
        self.resume_workflow_service = ResumeWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock
        )
        self.claim_agent_task_service = ClaimAgentTaskService(
            self.agent_task_repository, self.workflow_instance_repository, self.clock
        )
        self.complete_workflow_service = CompleteWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock
        )
        self.fail_workflow_service = FailWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock
        )
        self.complete_agent_task_service = CompleteAgentTaskService(
            self.agent_task_repository, self.workflow_instance_repository, self.checkpoint_repository, self.outbox_repository,
            self.command_idempotency_repository, self.clock, self.coordinate_agent_tasks_service,
            self.complete_workflow_service, self.fail_workflow_service,
        )
        self.request_tool_service = RequestToolService(
            self.checkpoint_repository, self.tool_request_repository, self.tool_gateway_port,
            self.command_idempotency_repository, self.clock,
        )
        self.consume_runtime_event_service = ConsumeRuntimeEventService(
            self.processed_event_repository, self.workflow_instance_repository, self.clock
        )
        self.consume_ticket_created_service = ConsumeTicketCreatedService(
            self.processed_event_repository, self.ticket_snapshot_port, self.workflow_definition_catalog_port,
            self.start_workflow_service, self.clock,
        )
        self.recover_workflow_service = RecoverWorkflowService(
            self.workflow_instance_repository, self.checkpoint_repository, self.agent_task_repository, self.clock
        )
        self.dispatch_outbox_events_service = DispatchOutboxEventsService(
            self.outbox_repository, self.event_publisher_port, self.clock
        )
        self.cancel_workflow_service = CancelWorkflowService(
            self.workflow_instance_repository, self.outbox_repository, self.command_idempotency_repository, self.clock
        )
        self.workflow_query_service = WorkflowQueryService(self.workflow_instance_repository, self.checkpoint_repository)
        self.agent_task_query_service = AgentTaskQueryService(self.agent_task_repository)

        self.workflow_command_port: WorkflowCommandPort = WorkflowCommandService(
            self.start_workflow_service, self.pause_workflow_service, self.resume_workflow_service
        )
        self.agent_task_command_port: AgentTaskCommandPort = AgentTaskCommandService(
            self.claim_agent_task_service, self.complete_agent_task_service, self.request_tool_service
        )
        self.runtime_event_consumer_port: RuntimeEventConsumerPort = self.consume_runtime_event_service
        self.ticket_created_consumer_port: TicketCreatedConsumerPort = self.consume_ticket_created_service
        self.recovery_port: RecoveryPort = self.recover_workflow_service
        self.outbox_dispatch_port: OutboxDispatchPort = self.dispatch_outbox_events_service
        self.workflow_lifecycle_port: WorkflowLifecyclePort = WorkflowLifecycleService(
            self.complete_workflow_service, self.fail_workflow_service, self.cancel_workflow_service
        )
        self.workflow_query_port: WorkflowQueryPort = self.workflow_query_service
        self.agent_task_query_port: AgentTaskQueryPort = self.agent_task_query_service


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


def get_recovery_port() -> RecoveryPort:
    return get_container().recovery_port


def get_outbox_dispatch_port() -> OutboxDispatchPort:
    return get_container().outbox_dispatch_port


def get_workflow_lifecycle_port() -> WorkflowLifecyclePort:
    return get_container().workflow_lifecycle_port


def get_workflow_query_port() -> WorkflowQueryPort:
    return get_container().workflow_query_port


def get_agent_task_query_port() -> AgentTaskQueryPort:
    return get_container().agent_task_query_port
