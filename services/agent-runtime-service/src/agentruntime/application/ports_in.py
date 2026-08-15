"""Input ports (13-package-and-class-design §"Ports")."""

from __future__ import annotations

import uuid
from typing import Protocol

from agentruntime.application.commands import (
    CancelWorkflowCommand,
    ClaimAgentTaskCommand,
    ClaimReadyAgentTasksCommand,
    CompleteAgentTaskCommand,
    CompleteWorkflowCommand,
    ConsumeTicketCancelledCommand,
    ConsumeTicketCreatedCommand,
    ConsumeTicketReopenedCommand,
    FailWorkflowCommand,
    ForceRecoverWorkflowCommand,
    PauseWorkflowCommand,
    RecoveryCommand,
    RequestToolCommand,
    ResumeWorkflowCommand,
    RetryAgentTaskCommand,
    RuntimeEventEnvelope,
    StartWorkflowCommand,
)
from agentruntime.application.records import AuditRecordEntry
from agentruntime.application.views import (
    AgentTaskView,
    CheckpointView,
    DispatchReport,
    DispatchToolRequestsReport,
    LeaseRecoveryReport,
    PoisonEventView,
    RecoveryReport,
    RecoveryScanReport,
    ToolRequestView,
    WorkflowInstanceView,
)
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


class TicketCycleConsumerPort(Protocol):
    """Input port for the two ticket-cycle-ending events (SPEC-ARO-023 06-event-contracts):
    ticket.cancelled.v1 and ticket.reopened.v1. Implemented directly by
    ConsumeTicketCycleEventService. Separate from RuntimeEventConsumerPort for the same
    reason TicketCreatedConsumerPort is: neither event carries this Runtime's own
    workflow_instance_id — Ticket Workflow only knows ticketId + ticketCycleId.
    """

    def consume_cancelled(self, command: ConsumeTicketCancelledCommand) -> bool:
        """Returns True if the event was newly processed, False if it was a duplicate."""
        ...

    def consume_reopened(self, command: ConsumeTicketReopenedCommand) -> bool:
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

    def scan_and_recover(self, batch_size: int) -> RecoveryScanReport:
        """SPEC-ARO-028 10-failure-handling §"Runtime 崩溃后怎么恢复": the batch
        "Recovery worker 周期性扫描非终态 Workflow Instance" this section describes.
        """
        ...

    def force_recover(self, command: ForceRecoverWorkflowCommand) -> RecoveryScanReport:
        """SPEC-ARO-031 05-api-contracts §"Admin API": "force recover workflow" — the
        admin-triggered, single-instance counterpart to scan_and_recover()'s own
        periodic batch sweep. Never reaches a terminal Workflow Instance: 03-state-
        machine's own FAILED/COMPLETED/CANCELLED stay one-way.
        """
        ...


class LeaseRecoveryPort(Protocol):
    """Input port for expired-lease Agent Task recovery. Implemented directly by
    RecoverExpiredLeaseTasksService. SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么
    恢复" step 5, kept separate from RecoveryPort (Workflow Instance-scoped, SPEC-ARO-028)
    since this scanner's unit of work is the Agent Task, not the Workflow Instance.
    """

    def scan_and_recover(self, batch_size: int) -> LeaseRecoveryReport: ...

    def retry_task(self, command: RetryAgentTaskCommand) -> AgentTaskView:
        """SPEC-ARO-031 05-api-contracts §"Admin API": "retry failed task" — the
        admin-triggered, single-task counterpart to scan_and_recover()'s own automatic
        expired-lease discovery. Never reaches a terminal Agent Task: 03-state-machine's
        own FAILED_FINAL stays non-retryable.
        """
        ...


class PoisonEventQueryPort(Protocol):
    """Input port for the SPEC-ARO-024 10-failure-handling §"Poison Event" step 4
    visibility surface. Implemented directly by PoisonEventQueryService.
    """

    def list_poison_events(self, limit: int) -> list[PoisonEventView]: ...


class AuditRecordQueryPort(Protocol):
    """Input port for SPEC-ARO-034 12-observability §"Audit Events" visibility.
    Implemented directly by AuditRecordQueryService. A persisted-but-inaccessible
    audit trail is of limited operational value, mirroring
    PoisonEventQueryPort's own reasoning.
    """

    def list_audit_events(self, limit: int) -> list[AuditRecordEntry]: ...


class PoisonEventCommandPort(Protocol):
    """Input port for SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event
    quarantined." Implemented directly by PoisonEventQueryService — kept as a separate
    Protocol from PoisonEventQueryPort so the pure-visibility surface stays undisturbed
    by the addition of a write.
    """

    def mark_quarantined(self, id: uuid.UUID) -> PoisonEventView: ...


class OutboxDispatchPort(Protocol):
    """Input port for outbox publishing. Implemented directly by DispatchOutboxEventsService.
    08-transaction-and-outbox §"Outbox Publisher".
    """

    def dispatch_due_events(self, batch_size: int) -> DispatchReport: ...

    def replay_dead_letter(self, batch_size: int) -> DispatchReport:
        """SPEC-ARO-030 10-failure-handling §"Runtime 崩溃后怎么恢复" step 3: "重放未发布
        outbox" — the manual/ops intervention OutboxStatus.DEAD_LETTER's own docstring
        names.
        """
        ...


class ToolDispatchPort(Protocol):
    """Input port for Tool Request dispatch. Implemented directly by
    DispatchToolRequestsService. SPEC-ARO-019 08-transaction-and-outbox §"Tool Request
    Transaction" step 6 — the ToolGatewayPort analogue of OutboxDispatchPort.
    """

    def dispatch_pending_requests(self, batch_size: int) -> DispatchToolRequestsReport: ...
