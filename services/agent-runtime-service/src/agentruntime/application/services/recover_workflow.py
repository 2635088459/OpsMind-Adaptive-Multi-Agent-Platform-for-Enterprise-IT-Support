"""13-package-and-class-design §"Application Layer": RecoverWorkflowService, the sole
implementation of RecoveryPort. 02-business-invariants: "Runtime must recover from
checkpoints, leases, cursors, and outbox after crash" — this service demonstrates
that recoverability by reconstructing a RecoveryReport purely from persisted state,
never from anything held only in memory. A full reconciliation sweep (lease expiry
requeue, outbox redelivery) is phase-08 (failure-recovery-reconciliation);
SPEC-ARO-001 fixes the module boundary and the "must not silently switch
definitions" guard.
"""

from __future__ import annotations

from agentruntime.application.commands import RecoveryCommand
from agentruntime.application.exceptions import DefinitionVersionMismatchException, WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import AgentTaskRepository, CheckpointRepository, ClockPort, WorkflowInstanceRepository
from agentruntime.application.views import RecoveryReport
from agentruntime.domain.enums import AgentTaskState


class RecoverWorkflowService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        checkpoint_repository: CheckpointRepository,
        agent_task_repository: AgentTaskRepository,
        clock: ClockPort,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._checkpoint_repository = checkpoint_repository
        self._agent_task_repository = agent_task_repository
        self._clock = clock

    def recover(self, command: RecoveryCommand) -> RecoveryReport:
        workflow = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

        if command.expected_definition_version is not None and command.expected_definition_version != workflow.definition_version:
            raise DefinitionVersionMismatchException(workflow.definition_version, command.expected_definition_version)

        recoverable_checkpoint_count = len(self._checkpoint_repository.find_by_workflow_instance_id(workflow.id))
        open_lease_count = sum(
            1
            for task in self._agent_task_repository.find_by_workflow_instance_id(workflow.id)
            if task.state in (AgentTaskState.CLAIMED, AgentTaskState.RUNNING) and task.is_lease_outstanding()
        )

        return RecoveryReport(
            workflow_instance_id=workflow.id, state=workflow.state, workflow_version=workflow.workflow_version,
            definition_version=workflow.definition_version, recoverable_checkpoint_count=recoverable_checkpoint_count,
            open_lease_count=open_lease_count, recovered_at=self._clock.now(),
        )
