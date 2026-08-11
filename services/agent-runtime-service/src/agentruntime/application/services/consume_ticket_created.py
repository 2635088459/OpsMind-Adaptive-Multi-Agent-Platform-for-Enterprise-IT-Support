"""13-package-and-class-design §"Application Layer": ConsumeTicketCreatedService, the
sole implementation of TicketCreatedConsumerPort. SPEC-ARO-005 04-use-cases UC-01
"Consume ticket.created": dedup by processed_events (step 2), confirm automation can
start against the Ticket snapshot (step 3), then delegate instance creation, the STARTED
checkpoint, Planner task-graph materialization, and workflow.started publication (steps
4-8) to StartWorkflowService — the one place that logic lives, shared with the direct
REST /workflows command (see StartWorkflowService's own docstring).

Deliberately its own service rather than a branch inside ConsumeRuntimeEventService: that
service's envelope requires an already-existing workflow_instance_id to look up, which
ticket.created structurally cannot supply (SPEC-ARO-001's per-event-type-application
deferral this fulfils).
"""

from __future__ import annotations

from agentruntime.application.commands import ConsumeTicketCreatedCommand, StartWorkflowCommand
from agentruntime.application.exceptions import AutomationNotAllowedException
from agentruntime.application.ports_out import ClockPort, ProcessedEventRepository, TicketSnapshotPort, WorkflowDefinitionCatalogPort
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.domain.ids import IdempotencyKey, TicketId, WorkflowInstanceId

# Java sibling's TicketStatus terminal values (dev.opsmind.ticketworkflow.ticket.domain
# .value.TicketStatus) that must block a late/redundant ticket.created from starting
# automation on a ticket that has already left automatable territory.
_TERMINAL_TICKET_STATUSES = frozenset({"RESOLVED", "CLOSED", "ESCALATED", "FAILED", "CANCELLED"})


class ConsumeTicketCreatedService:
    def __init__(
        self,
        processed_event_repository: ProcessedEventRepository,
        ticket_snapshot_port: TicketSnapshotPort,
        workflow_definition_catalog_port: WorkflowDefinitionCatalogPort,
        start_workflow_service: StartWorkflowService,
        clock: ClockPort,
    ) -> None:
        self._processed_event_repository = processed_event_repository
        self._ticket_snapshot_port = ticket_snapshot_port
        self._workflow_definition_catalog_port = workflow_definition_catalog_port
        self._start_workflow_service = start_workflow_service
        self._clock = clock

    def consume(self, command: ConsumeTicketCreatedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id):
            return False

        workflow_instance_id: WorkflowInstanceId | None = None
        try:
            self._ensure_automation_allowed(command.ticket_id)
            definition = self._workflow_definition_catalog_port.resolve_for_ticket(command.category)
            start_command = StartWorkflowCommand(
                command.ticket_id, command.ticket_cycle_id, definition, self._idempotency_key(command, definition.workflow_type)
            )
            view = self._start_workflow_service.start(start_command)
            workflow_instance_id = view.workflow_instance_id
        finally:
            self._processed_event_repository.mark_processed(
                command.event_id, self._clock.now(), command.event_type, workflow_instance_id
            )

        return True

    def _ensure_automation_allowed(self, ticket_id: TicketId) -> None:
        snapshot = self._ticket_snapshot_port.find_snapshot(ticket_id)
        if snapshot is not None and snapshot.ticket_status in _TERMINAL_TICKET_STATUSES:
            raise AutomationNotAllowedException(ticket_id, snapshot.ticket_status)

    def _idempotency_key(self, command: ConsumeTicketCreatedCommand, workflow_type: str) -> IdempotencyKey:
        """06-event-contracts: "Idempotency key: eventId or ticketId + ticketCycleId +
        workflowType." The composite, not eventId, so a retried ticket.created under a
        *different* eventId for the same logical ticket cycle still collapses onto the
        same Start command — eventId-based dedup above only catches literal redelivery of
        the exact same event.
        """
        return IdempotencyKey(f"ticket-created:{command.ticket_id}:{command.ticket_cycle_id}:{workflow_type}")
