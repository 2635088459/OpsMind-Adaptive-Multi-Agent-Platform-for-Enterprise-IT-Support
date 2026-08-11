"""Output ports (13-package-and-class-design §"Ports"). Structural typing.Protocol,
the idiomatic Python stand-in for Java interfaces — infrastructure adapters satisfy
these by shape, without inheriting from anything here.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Protocol

from agentruntime.application.commands import WorkflowDefinitionInput
from agentruntime.application.records import (
    AgentTaskRecord,
    CheckpointRecord,
    CommandIdempotencyRecord,
    OutboxRecord,
    TicketSnapshot,
    ToolDispatchAcknowledgement,
    ToolRequestRecord,
    WorkflowInstanceRecord,
)
from agentruntime.domain.enums import CheckpointType
from agentruntime.domain.ids import (
    AgentTaskId,
    IdempotencyKey,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowInstanceId,
    WorkflowType,
)


class ClockPort(Protocol):
    def now(self) -> datetime: ...


class WorkflowInstanceRepository(Protocol):
    def find_by_id(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowInstanceRecord | None: ...

    def find_active_by_ticket_cycle_and_type(
        self, ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_type: WorkflowType
    ) -> WorkflowInstanceRecord | None:
        """02-business-invariants §"Workflow Instance Invariants": at most one active instance per key."""
        ...

    def find_by_ticket_id(self, ticket_id: TicketId) -> list[WorkflowInstanceRecord]:
        """SPEC-ARO-006 05-api-contracts "GET /workflows/by-ticket/{ticketId}": every Workflow
        Instance ever started for a ticket, across all ticket cycles and terminal/
        non-terminal states — unlike find_active_by_ticket_cycle_and_type, not filtered
        to "currently active".
        """
        ...

    def save(self, record: WorkflowInstanceRecord) -> WorkflowInstanceRecord:
        """Inserts a brand new record, or replaces an existing one under optimistic-concurrency
        control: the stored record's workflow_version must equal record.workflow_version - 1.

        Raises WorkflowInstanceVersionConflictException if the stored version has moved on.
        """
        ...


class AgentTaskRepository(Protocol):
    def find_by_id(self, agent_task_id: AgentTaskId) -> AgentTaskRecord | None: ...

    def find_by_workflow_instance_id_and_task_key(
        self, workflow_instance_id: WorkflowInstanceId, task_key: str
    ) -> AgentTaskRecord | None: ...

    def find_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> list[AgentTaskRecord]: ...

    def find_claimable_ready_tasks(self, agent_role: str, limit: int) -> list[AgentTaskRecord]:
        """SPEC-ARO-009 05-api-contracts "Claim Task" / 09-concurrency-and-idempotency:
        "Use FOR UPDATE SKIP LOCKED or an equivalent mechanism to prevent multiple workers
        from claiming the same task." Up to `limit` AgentTaskState.READY rows whose
        agent_role matches, oldest first — steers concurrent pollers away from rows
        another in-flight transaction already has under consideration (Postgres:
        FOR UPDATE SKIP LOCKED), but does not by itself guarantee exclusivity: the actual,
        unconditional safety net is the same optimistic task_version check every save()
        already enforces (ClaimAgentTaskService.claim_ready() treats a version conflict on
        a candidate from this list as "lost the race", not an error). Does not filter by
        the owning Workflow Instance's state — the caller checks that per candidate, since
        an in-memory adapter has no cross-repository join to express it either.
        """
        ...

    def save(self, record: AgentTaskRecord) -> AgentTaskRecord:
        """Inserts a brand new record, or replaces an existing one under optimistic-concurrency
        control: the stored record's task_version must equal record.task_version - 1.

        Raises AgentTaskVersionConflictException if the stored version has moved on.
        """
        ...


class CheckpointRepository(Protocol):
    """02-business-invariants §"Checkpoint Invariants": "A checkpoint must exist before any
    external side effect."
    """

    def save(self, record: CheckpointRecord) -> CheckpointRecord: ...

    def find_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> list[CheckpointRecord]: ...

    def find_latest_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> CheckpointRecord | None:
        """SPEC-ARO-006 05-api-contracts "GET /workflows/{workflowInstanceId}/checkpoints/
        latest" — most-recently-recorded checkpoint, or None if the instance has none yet.
        """
        ...

    def find_latest_by_workflow_instance_id_and_type(
        self, workflow_instance_id: WorkflowInstanceId, checkpoint_type: CheckpointType
    ) -> CheckpointRecord | None:
        """SPEC-ARO-008 04-use-cases UC-02 step 6: CoordinateAgentTasksService.
        unlock_downstream_tasks() uses this with CheckpointType.STARTED to re-derive the
        task graph — the only durable record of its shape (no Workflow Definition catalog
        table exists) — after a later task completes. STARTED is written exactly once per
        instance, so "latest" and "only" coincide here, but the method stays generically
        named/typed for any other checkpoint type a future caller needs the newest of.
        """
        ...


class ToolRequestRepository(Protocol):
    def save(self, record: ToolRequestRecord) -> ToolRequestRecord: ...

    def find_by_id(self, tool_request_id: ToolRequestId) -> ToolRequestRecord | None: ...


class ProcessedEventRepository(Protocol):
    """02-business-invariants §"Event Handling Invariants": "Every consumed event must be
    checked against or written to processed_events in the same transaction."
    """

    def is_processed(self, event_id: str) -> bool: ...

    def mark_processed(
        self,
        event_id: str,
        processed_at: datetime,
        event_type: str | None = None,
        workflow_instance_id: WorkflowInstanceId | None = None,
    ) -> None: ...


class OutboxRepository(Protocol):
    """02-business-invariants §"Event Handling Invariants": "Every published event must go
    through outbox. Broker messages must not be sent synchronously inside business
    transactions." 08-transaction-and-outbox §"Outbox Publisher": DispatchOutboxEventsService
    is the only caller of find_dispatchable/mark_*; every other application service only
    ever calls append(). Real broker wiring (RabbitMQ) is phase-07
    (runtime-event-publishing) — DispatchOutboxEventsService publishes through
    EventPublisherPort, not directly.
    """

    def append(self, record: OutboxRecord) -> None: ...

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        """08-transaction-and-outbox §"Outbox Publisher": "Scan by available_at."."""
        ...

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None: ...

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None: ...

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None: ...


class ToolGatewayPort(Protocol):
    """13-package-and-class-design §"Class Boundaries": "ToolGatewayPort sends only
    persisted Tool Requests." 02-business-invariants §"Tool Gateway Boundary": this is
    Runtime's only exit to tool systems — Agent SDKs, Agent Workers, and Task Handlers must
    never hold a Tool client directly, and no application service other than
    RequestToolService may depend on this port (enforced by
    tests/architecture/test_tool_gateway_boundary.py).
    """

    def dispatch(self, request: ToolRequestRecord) -> ToolDispatchAcknowledgement: ...


class TicketSnapshotPort(Protocol):
    """02-business-invariants §"State Ownership": read-only view of Ticket state; never a
    write path.
    """

    def find_snapshot(self, ticket_id: TicketId) -> TicketSnapshot | None: ...


class WorkflowDefinitionCatalogPort(Protocol):
    """SPEC-ARO-005 04-use-cases UC-01 step 6: "Planner creates the Agent Task graph" —
    the Planner (domain.planner.plan()) only resolves a graph out of an already-bound
    WorkflowDefinition; something has to supply that definition when Runtime itself
    decides to start a Workflow Instance from a consumed ticket.created event, since
    there the caller carries only ticketId/ticketCycleId/priority/category, never a full
    definition (unlike the direct REST /workflows command, which still requires the
    caller to supply one — see StartWorkflowCommand's docstring). A full authorable
    Workflow Definition catalog (table, versioning, category-routing rules) has no
    07-data-model table yet and stays out of scope here — same "capabilities reserved
    for later phases" deferral domain.planner.plan() already documents for phase-02
    agent-task-orchestration.
    """

    def resolve_for_ticket(self, category: str) -> WorkflowDefinitionInput: ...


class EventPublisherPort(Protocol):
    """08-transaction-and-outbox §"Outbox Publisher". Runtime's only exit for publishing —
    mirrors ToolGatewayPort's boundary role, but for outbound events instead of tool
    calls: only DispatchOutboxEventsService may depend on this port. Real RabbitMQ wiring
    is phase-07 (runtime-event-publishing); SPEC-ARO-003 ships a logging adapter.
    """

    def publish(self, record: OutboxRecord) -> bool:
        """Returns True on success. Must never raise for an ordinary delivery failure —
        DispatchOutboxEventsService interprets False as "retry with backoff", not an
        application bug.
        """
        ...


class CommandIdempotencyRepository(Protocol):
    """07-data-model §"command_idempotency" / 09-concurrency-and-idempotency
    §"Command Idempotency". See CommandIdempotencyRecord's docstring for who uses it.
    """

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None: ...

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord: ...
