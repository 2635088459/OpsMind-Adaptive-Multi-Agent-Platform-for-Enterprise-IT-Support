"""Application-layer commands and input DTOs, consumed by agentruntime.application.services.

Plain dataclasses — deliberately framework-free (no pydantic), mirroring
agentruntime.domain: the interfaces layer's pydantic request models map onto
these, never the other way around.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

from agentruntime.domain.ids import (
    AgentTaskId,
    CausationId,
    CorrelationId,
    DefinitionVersion,
    IdempotencyKey,
    LeaseToken,
    TicketCycleId,
    TicketId,
    WorkflowInstanceId,
)


@dataclass(frozen=True, slots=True)
class TaskNodeInput:
    """Application-layer input shape for one TaskGraph node. Keeps
    domain.task_graph.TaskNode (and its JoinPolicy enum) out of the interfaces layer's
    dependency graph — only StartWorkflowService translates this into the domain type
    (13-package-and-class-design §"Interfaces": "Controllers ... only perform request
    validation, auth, and DTO mapping").
    """

    task_key: str
    task_type: str
    depends_on: frozenset[str]
    join_policy: str
    # SPEC-ARO-009: see domain.task_graph.TaskNode.agent_role's docstring for why this
    # is optional rather than required.
    agent_role: str | None = None


@dataclass(frozen=True, slots=True)
class WorkflowDefinitionInput:
    """Application-layer input shape for a Workflow Definition; see TaskNodeInput."""

    definition_id: str
    definition_version: int
    workflow_type: str
    task_graph: tuple[TaskNodeInput, ...]


@dataclass(frozen=True, slots=True)
class StartWorkflowCommand:
    """definition is supplied by the caller already resolved (the Workflow Definition
    catalog is out of SPEC-ARO-001's scope) so StartWorkflowService never has to reach
    outside its own ports to plan the initial task graph (13-package-and-class-design:
    "Planner creates task graph but does not call tools"). 09-concurrency-and-idempotency
    §"Command Idempotency": "Start ... must include idempotencyKey."
    """

    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    definition: WorkflowDefinitionInput
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class PauseWorkflowCommand:
    workflow_instance_id: WorkflowInstanceId
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class ResumeWorkflowCommand:
    workflow_instance_id: WorkflowInstanceId
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class CompleteWorkflowCommand:
    """SPEC-ARO-004 Workflow Instance Aggregate: records that Runtime automation for this
    instance is done. *Deciding when* completion is due is a task-graph/join-policy question
    owned by SPEC-ARO-010 (task-completion-and-join-policy); this command only carries out
    the aggregate-level transition once that decision has been made, reachable today through
    the admin surface (mirrors RecoveryCommand being admin-triggered ahead of an automatic
    scheduler).
    """

    workflow_instance_id: WorkflowInstanceId
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class FailWorkflowCommand:
    """02-business-invariants: failure paths must retain an auditable reason."""

    workflow_instance_id: WorkflowInstanceId
    idempotency_key: IdempotencyKey
    failure_reason: str

    def __post_init__(self) -> None:
        if not self.failure_reason or not self.failure_reason.strip():
            raise ValueError("failure_reason must not be blank")


@dataclass(frozen=True, slots=True)
class CancelWorkflowCommand:
    """SPEC-ARO-004: aggregate-level cancel operation. *Triggering* a cancel from an
    upstream ticket-cycle cancellation event is owned by SPEC-ARO-023
    (consume-ticket-cycle-events); this command carries out the transition itself.
    """

    workflow_instance_id: WorkflowInstanceId
    idempotency_key: IdempotencyKey
    reason: str

    def __post_init__(self) -> None:
        if not self.reason or not self.reason.strip():
            raise ValueError("reason must not be blank")


@dataclass(frozen=True, slots=True)
class ClaimAgentTaskCommand:
    workflow_instance_id: WorkflowInstanceId
    task_key: str
    worker_id: str
    lease_seconds: int

    def __post_init__(self) -> None:
        if not self.task_key or not self.task_key.strip():
            raise ValueError("task_key must not be blank")
        if not self.worker_id or not self.worker_id.strip():
            raise ValueError("worker_id must not be blank")
        if self.lease_seconds <= 0:
            raise ValueError("lease_seconds must be positive")


@dataclass(frozen=True, slots=True)
class ClaimReadyAgentTasksCommand:
    """SPEC-ARO-009 05-api-contracts "Claim Task": "Worker provides agentRole, workerId,
    and maxTasks. Service returns tasks with leases." The role-based batch counterpart
    to ClaimAgentTaskCommand — this one doesn't name a specific workflow_instance_id or
    task_key because a polling Agent Worker doesn't know either in advance.
    """

    agent_role: str
    worker_id: str
    max_tasks: int
    lease_seconds: int

    def __post_init__(self) -> None:
        if not self.agent_role or not self.agent_role.strip():
            raise ValueError("agent_role must not be blank")
        if not self.worker_id or not self.worker_id.strip():
            raise ValueError("worker_id must not be blank")
        if self.max_tasks <= 0:
            raise ValueError("max_tasks must be positive")
        if self.lease_seconds <= 0:
            raise ValueError("lease_seconds must be positive")


@dataclass(frozen=True, slots=True)
class CompleteAgentTaskCommand:
    """02-business-invariants §"Agent Task Invariants": "Task completion must write either a
    result payload or an explicit failure reason" — exactly one of result_payload /
    failure_reason must be set. 09-concurrency-and-idempotency §"Task Claim": "Worker
    completion must submit claimToken. Mismatch is rejected." §"Command Idempotency":
    "... Complete Task ... must include idempotencyKey." §"Workflow Version": "Task
    worker receives workflowVersion when reading a task and must validate it on result
    submission" — workflow_version is keyword-only (not just appended positionally)
    specifically so a caller passing the pre-SPEC-ARO-009 positional argument order fails
    loudly (missing required argument) instead of silently binding some other value into
    the wrong slot.
    """

    agent_task_id: AgentTaskId
    claim_token: LeaseToken
    idempotency_key: IdempotencyKey
    workflow_version: int = field(kw_only=True)
    result_payload: str | None = None
    failure_reason: str | None = None

    def __post_init__(self) -> None:
        has_result = bool(self.result_payload and self.result_payload.strip())
        has_failure = bool(self.failure_reason and self.failure_reason.strip())
        if has_result == has_failure:
            raise ValueError("exactly one of result_payload or failure_reason must be provided")

    @property
    def is_success(self) -> bool:
        return self.result_payload is not None


@dataclass(frozen=True, slots=True)
class RequestToolCommand:
    """02-business-invariants §"Tool Gateway Boundary": handled exclusively by
    RequestToolService, the only application service allowed to reach ToolGatewayPort.
    checkpoint_payload is written before the tool is dispatched (§"Checkpoint Invariants").
    09-concurrency-and-idempotency §"Command Idempotency": "... Request Tool must include
    idempotencyKey."
    """

    workflow_instance_id: WorkflowInstanceId
    agent_task_id: AgentTaskId
    checkpoint_payload: str
    tool_name: str
    tool_request_payload: str
    idempotency_key: IdempotencyKey

    def __post_init__(self) -> None:
        if not self.checkpoint_payload or not self.checkpoint_payload.strip():
            raise ValueError("checkpoint_payload must not be blank")
        if not self.tool_name or not self.tool_name.strip():
            raise ValueError("tool_name must not be blank")


@dataclass(frozen=True, slots=True)
class RecoveryCommand:
    workflow_instance_id: WorkflowInstanceId
    expected_definition_version: DefinitionVersion | None = None


@dataclass(frozen=True, slots=True)
class RuntimeEventEnvelope:
    """SPEC-ARO-001 event-contract: "Event envelope must include correlationId, causationId,
    ticketId, and workflowInstanceId" for every consumed event. expected_workflow_version,
    when present, lets ConsumeRuntimeEventService detect a stale event without needing a
    per-stream cursor store (SPEC-ARO-003 introduces the durable cursor).
    """

    event_id: str
    event_type: str
    producer: str
    schema_version: int
    correlation_id: CorrelationId
    causation_id: CausationId
    ticket_id: TicketId
    workflow_instance_id: WorkflowInstanceId
    occurred_at: datetime
    payload: str
    expected_workflow_version: int | None = field(default=None)

    def __post_init__(self) -> None:
        if not self.event_id or not self.event_id.strip():
            raise ValueError("event_id must not be blank")
        if not self.event_type or not self.event_type.strip():
            raise ValueError("event_type must not be blank")
        if not self.producer or not self.producer.strip():
            raise ValueError("producer must not be blank")
        if self.schema_version < 1:
            raise ValueError("schema_version must be at least 1")


@dataclass(frozen=True, slots=True)
class ConsumeTicketCreatedCommand:
    """SPEC-ARO-005 04-use-cases UC-01 / 06-event-contracts "ticket.created.v1". Deliberately
    a separate shape from RuntimeEventEnvelope rather than reusing it with a nullable
    workflow_instance_id: ticket.created is structurally different from every other
    consumed event — no Workflow Instance exists yet for ConsumeRuntimeEventService's
    envelope.workflow_instance_id lookup to find, because creating one is the whole point
    of this command. category/priority/created_by mirror the event's own "Key fields" list.
    """

    event_id: str
    event_type: str
    producer: str
    schema_version: int
    correlation_id: CorrelationId
    causation_id: CausationId
    ticket_id: TicketId
    ticket_cycle_id: TicketCycleId
    priority: str
    category: str
    created_by: str
    occurred_at: datetime

    def __post_init__(self) -> None:
        if not self.event_id or not self.event_id.strip():
            raise ValueError("event_id must not be blank")
        if not self.event_type or not self.event_type.strip():
            raise ValueError("event_type must not be blank")
        if not self.producer or not self.producer.strip():
            raise ValueError("producer must not be blank")
        if self.schema_version < 1:
            raise ValueError("schema_version must be at least 1")
        if not self.category or not self.category.strip():
            raise ValueError("category must not be blank")
