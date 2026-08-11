"""Input ports (13-package-and-class-design §"Ports")."""

from __future__ import annotations

from typing import Protocol

from agentruntime.application.commands import (
    CancelWorkflowCommand,
    ClaimAgentTaskCommand,
    ClaimReadyAgentTasksCommand,
    CompleteAgentTaskCommand,
    CompleteWorkflowCommand,
    ConsumeTicketCreatedCommand,
    FailWorkflowCommand,
    PauseWorkflowCommand,
    RecoveryCommand,
    RequestToolCommand,
    ResumeWorkflowCommand,
    RuntimeEventEnvelope,
    StartWorkflowCommand,
)
from agentruntime.application.views import AgentTaskView, CheckpointView, DispatchReport, RecoveryReport, ToolRequestView, WorkflowInstanceView
from agentruntime.domain.ids import AgentTaskId, TicketId, WorkflowInstanceId


class WorkflowCommandPort(Protocol):
    """Input port for Workflow Instance commands. Implemented by WorkflowCommandService,
    which composes StartWorkflowService, PauseWorkflowService, and ResumeWorkflowService.
    SPEC-ARO-001 api-contract: "Commands must carry idempotency key or workflow version."
    """

    def start_workflow(self, command: StartWorkflowCommand) -> WorkflowInstanceView: ...

    def pause_workflow(self, command: PauseWorkflowCommand) -> WorkflowInstanceView: ...

    def resume_workflow(self, command: ResumeWorkflowCommand) -> WorkflowInstanceView: ...


class WorkflowLifecyclePort(Protocol):
    """Input port for the Workflow Instance Aggregate's terminal transitions
    (SPEC-ARO-004). Implemented by WorkflowLifecycleService, which composes
    CompleteWorkflowService, FailWorkflowService, and CancelWorkflowService. Reached
    through the admin surface today — see agentruntime.interfaces.admin.router.
    """

    def complete_workflow(self, command: CompleteWorkflowCommand) -> WorkflowInstanceView: ...

    def fail_workflow(self, command: FailWorkflowCommand) -> WorkflowInstanceView: ...

    def cancel_workflow(self, command: CancelWorkflowCommand) -> WorkflowInstanceView: ...


class AgentTaskCommandPort(Protocol):
    """Input port for Agent Task and Tool Request commands. Implemented by
    AgentTaskCommandService, which composes ClaimAgentTaskService, CompleteAgentTaskService,
    and RequestToolService.
    """

    def claim_agent_task(self, command: ClaimAgentTaskCommand) -> AgentTaskView: ...

    def claim_ready_agent_tasks(self, command: ClaimReadyAgentTasksCommand) -> list[AgentTaskView]: ...

    def complete_agent_task(self, command: CompleteAgentTaskCommand) -> AgentTaskView: ...

    def request_tool(self, command: RequestToolCommand) -> ToolRequestView: ...


class RuntimeEventConsumerPort(Protocol):
    """Input port for external events consumed by Runtime. Implemented directly by
    ConsumeRuntimeEventService. 02-business-invariants §"Event Handling Invariants": "Every
    consumed event must be checked against or written to processed_events."
    """

    def consume(self, envelope: RuntimeEventEnvelope) -> bool:
        """Returns True if the event was newly processed, False if it was a duplicate/stale no-op."""
        ...


class TicketCreatedConsumerPort(Protocol):
    """Input port for ticket.created (SPEC-ARO-005 04-use-cases UC-01). Implemented
    directly by ConsumeTicketCreatedService. Separate from RuntimeEventConsumerPort:
    ticket.created carries no workflow_instance_id (none exists yet), so it cannot share
    RuntimeEventEnvelope's shape.
    """

    def consume(self, command: ConsumeTicketCreatedCommand) -> bool:
        """Returns True if the event was newly processed, False if it was a duplicate."""
        ...


class WorkflowQueryPort(Protocol):
    """Input port for read-only Workflow Instance/Checkpoint queries (SPEC-ARO-006
    05-api-contracts "Query API"). Implemented directly by WorkflowQueryService. Distinct
    from WorkflowCommandPort: no method here writes, versions, or requires an
    idempotency key — "Query APIs return Runtime state, not authoritative Ticket
    lifecycle decisions."
    """

    def find_workflow_instance(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowInstanceView: ...

    def find_workflow_instances_by_ticket(self, ticket_id: TicketId) -> list[WorkflowInstanceView]: ...

    def find_latest_checkpoint(self, workflow_instance_id: WorkflowInstanceId) -> CheckpointView: ...


class AgentTaskQueryPort(Protocol):
    """Input port for read-only Agent Task queries (SPEC-ARO-006 05-api-contracts "GET
    /agent-tasks/{agentTaskId}"). Implemented directly by AgentTaskQueryService.
    """

    def find_agent_task(self, agent_task_id: AgentTaskId) -> AgentTaskView: ...


class RecoveryPort(Protocol):
    """Input port for crash-recovery. Implemented directly by RecoverWorkflowService."""

    def recover(self, command: RecoveryCommand) -> RecoveryReport: ...


class OutboxDispatchPort(Protocol):
    """Input port for outbox publishing. Implemented directly by DispatchOutboxEventsService.
    08-transaction-and-outbox §"Outbox Publisher".
    """

    def dispatch_due_events(self, batch_size: int) -> DispatchReport: ...
