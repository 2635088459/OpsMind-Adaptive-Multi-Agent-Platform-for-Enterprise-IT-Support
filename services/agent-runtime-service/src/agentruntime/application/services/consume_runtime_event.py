"""13-package-and-class-design §"Application Layer": ConsumeRuntimeEventService, the
sole implementation of RuntimeEventConsumerPort. 02-business-invariants §"Event
Handling Invariants": "Every consumed event must be checked against or written to
processed_events" and SPEC-ARO-001 event-contract: "Duplicate/stale/invalid events
must not advance Workflow again."

Per-event-type application (approval decisions, tool results, etc.) is out of
SPEC-ARO-001's scope (phase-06 external-event-consumption); this service owns exactly
the dedup/staleness gate every consumer must pass through first.
"""

from __future__ import annotations

from agentruntime.application.commands import RuntimeEventEnvelope
from agentruntime.application.exceptions import StaleRuntimeEventException, WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import ClockPort, ProcessedEventRepository, WorkflowInstanceRepository


class ConsumeRuntimeEventService:
    def __init__(
        self,
        processed_event_repository: ProcessedEventRepository,
        workflow_instance_repository: WorkflowInstanceRepository,
        clock: ClockPort,
    ) -> None:
        self._processed_event_repository = processed_event_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._clock = clock

    def consume(self, envelope: RuntimeEventEnvelope) -> bool:
        if self._processed_event_repository.is_processed(envelope.event_id):
            return False

        workflow = self._workflow_instance_repository.find_by_id(envelope.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(envelope.workflow_instance_id)

        if envelope.expected_workflow_version is not None and envelope.expected_workflow_version != workflow.workflow_version:
            self._mark_processed(envelope)
            raise StaleRuntimeEventException(envelope.event_id)

        self._mark_processed(envelope)
        return True

    def _mark_processed(self, envelope: RuntimeEventEnvelope) -> None:
        self._processed_event_repository.mark_processed(
            envelope.event_id, self._clock.now(), envelope.event_type, envelope.workflow_instance_id
        )
