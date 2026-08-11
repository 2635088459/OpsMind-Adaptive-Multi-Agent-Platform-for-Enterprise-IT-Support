"""13-package-and-class-design §"Application Layer": WorkflowQueryService, the sole
implementation of WorkflowQueryPort. SPEC-ARO-006 05-api-contracts "Query API": "Query
APIs return Runtime state, not authoritative Ticket lifecycle decisions" — every method
here is a plain read of Runtime-owned state, no domain call, no write, no idempotency
guard (queries are not commands).
"""

from __future__ import annotations

from agentruntime.application.exceptions import CheckpointNotFoundException, WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import CheckpointRepository, WorkflowInstanceRepository
from agentruntime.application.views import CheckpointView, WorkflowInstanceView
from agentruntime.domain.ids import TicketId, WorkflowInstanceId


class WorkflowQueryService:
    def __init__(self, workflow_instance_repository: WorkflowInstanceRepository, checkpoint_repository: CheckpointRepository) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._checkpoint_repository = checkpoint_repository

    def find_workflow_instance(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowInstanceView:
        record = self._workflow_instance_repository.find_by_id(workflow_instance_id)
        if record is None:
            raise WorkflowInstanceNotFoundException(workflow_instance_id)
        return WorkflowInstanceView.from_record(record)

    def find_workflow_instances_by_ticket(self, ticket_id: TicketId) -> list[WorkflowInstanceView]:
        """Returns every Workflow Instance ever started for this ticket, terminal or not —
        an empty list is a valid, non-error result (no matching ticket found is not
        distinguishable from "no automation ever ran for this ticket", and neither is a
        Runtime concern to adjudicate).
        """
        records = self._workflow_instance_repository.find_by_ticket_id(ticket_id)
        return [WorkflowInstanceView.from_record(record) for record in records]

    def find_latest_checkpoint(self, workflow_instance_id: WorkflowInstanceId) -> CheckpointView:
        if self._workflow_instance_repository.find_by_id(workflow_instance_id) is None:
            raise WorkflowInstanceNotFoundException(workflow_instance_id)
        record = self._checkpoint_repository.find_latest_by_workflow_instance_id(workflow_instance_id)
        if record is None:
            raise CheckpointNotFoundException(workflow_instance_id)
        return CheckpointView.from_record(record)
