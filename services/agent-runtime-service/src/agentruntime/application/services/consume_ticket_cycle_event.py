"""13-package-and-class-design §"Application Layer": ConsumeTicketCycleEventService,
the sole implementation of TicketCycleConsumerPort. SPEC-ARO-023 06-event-contracts
(02-ticket-workflow's own contract, since domain 03's own 06-event-contracts names no
"ticket cycle" event of its own):

- PUB-014 "ticket.cancelled.v1" — Consumers: "Agent Runtime 取消 Workflow." The trigger
  CancelWorkflowCommand's own docstring already names this spec as owning: "Triggering a
  cancel from an upstream ticket-cycle cancellation event is owned by SPEC-ARO-023."
- PUB-015 "ticket.reopened.v1" — Consumers explicitly list Agent Runtime, carrying
  previousResolutionCycleId/newResolutionCycleId. A new cycle means whatever automation
  was still running under the *old* cycle is no longer relevant — the new cycle's own
  Workflow Instance is started separately, by a ticket.created.v1 delivery for that new
  cycle (unchanged SPEC-ARO-005 path), not by this service.

Neither event carries this Runtime's own workflow_instance_id (Ticket Workflow has no
knowledge of it) — both look up whatever Workflow Instance(s) are currently active for
(ticketId, ticketCycleId) via WorkflowInstanceRepository.find_by_ticket_id() (there is no
narrower query: "at most one active instance per ticketId+ticketCycleId+workflowType" is
a *per-type* invariant, so in principle more than one distinct workflow_type could be
active for the same ticket cycle, and cancellation must reach all of them, not just one),
then reuses the existing CancelWorkflowService for each — the codebase's one authorized,
already-idempotent, already-outbox-publishing path to WorkflowState.CANCELLED. No new
domain logic exists in this spec: domain.workflow_instance.cancel() already accepts any
non-terminal source state and already requires a non-blank reason.

10-failure-handling §"Poison Event": mark_processed happens in `finally` regardless of
whether cancelling downstream workflows succeeds, mirroring ConsumeTicketCreatedService's
own shape — a malformed or otherwise unprocessable event must not be retried forever.
"""

from __future__ import annotations

from agentruntime.application.commands import CancelWorkflowCommand, ConsumeTicketCancelledCommand, ConsumeTicketReopenedCommand
from agentruntime.application.ports_out import ClockPort, ProcessedEventRepository, WorkflowInstanceRepository
from agentruntime.application.services.cancel_workflow import CancelWorkflowService
from agentruntime.domain.exceptions import InvalidWorkflowStateException
from agentruntime.domain.ids import IdempotencyKey, TicketCycleId, TicketId

# SPEC-ARO-013 09-concurrency-and-idempotency §"消费事件幂等": this service's own identity
# in the (event_id, consumer_name) dedup key. One constant for both event types it
# handles, mirroring ConsumeRuntimeEventService's own single-consumer-many-event-types
# shape, since this is one logical consumer regardless of cancelled vs reopened.
CONSUMER_NAME = "consume_ticket_cycle_event"


class ConsumeTicketCycleEventService:
    def __init__(
        self,
        processed_event_repository: ProcessedEventRepository,
        workflow_instance_repository: WorkflowInstanceRepository,
        clock: ClockPort,
        cancel_workflow_service: CancelWorkflowService,
    ) -> None:
        self._processed_event_repository = processed_event_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._clock = clock
        self._cancel_workflow_service = cancel_workflow_service

    def consume_cancelled(self, command: ConsumeTicketCancelledCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False

        try:
            reason = f"ticket cancelled (reasonCode={command.cancel_reason_code})"
            self._cancel_active_workflows(command.ticket_id, command.ticket_cycle_id, "ticket-cancelled", reason)
        finally:
            self._processed_event_repository.mark_processed(command.event_id, CONSUMER_NAME, self._clock.now(), command.event_type, None)

        return True

    def consume_reopened(self, command: ConsumeTicketReopenedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, CONSUMER_NAME):
            return False

        try:
            reason = f"ticket reopened into a new cycle (reasonCode={command.reason_code})"
            self._cancel_active_workflows(command.ticket_id, command.previous_ticket_cycle_id, "ticket-reopened", reason)
        finally:
            self._processed_event_repository.mark_processed(command.event_id, CONSUMER_NAME, self._clock.now(), command.event_type, None)

        return True

    def _cancel_active_workflows(self, ticket_id: TicketId, ticket_cycle_id: TicketCycleId, trigger: str, reason: str) -> None:
        candidates = [
            record for record in self._workflow_instance_repository.find_by_ticket_id(ticket_id)
            if record.ticket_cycle_id == ticket_cycle_id and not record.state.is_terminal()
        ]
        for record in candidates:
            idempotency_key = IdempotencyKey(f"{trigger}:{record.id}")
            try:
                self._cancel_workflow_service.cancel(CancelWorkflowCommand(record.id, idempotency_key, reason))
            except InvalidWorkflowStateException:
                pass  # lost a race against something else that already moved this workflow to terminal
