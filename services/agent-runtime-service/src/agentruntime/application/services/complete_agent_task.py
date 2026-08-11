"""13-package-and-class-design §"Application Layer": CompleteAgentTaskService.
02-business-invariants §"Agent Task Invariants": "Task completion must write either a
result payload or an explicit failure reason" and "Task completion event may be
published once" — the second guarantee comes from domain.agent_task.complete()/fail()
only accepting a CLAIMED/RUNNING source state, so a second call against an
already-terminal record fails fast. 09-concurrency-and-idempotency §"Task Claim":
"Worker completion must submit claimToken. Mismatch is rejected." §"Workflow Version":
"For pause/resume, it must also validate pauseGeneration." §"Command Idempotency":
"... Complete Task ... must include idempotencyKey." SPEC-ARO-008 04-use-cases UC-02
steps 5-6: writes the AFTER_TASK checkpoint and re-invokes CoordinateAgentTasksService to
unlock whatever just became runnable — a completion or failure is exactly the trigger
UC-02 describes, regardless of which outcome it was. SPEC-ARO-010 08-transaction-and-
outbox §"Task Complete Transaction" step 6: once unlocking leaves nothing else to do,
drives the Workflow Instance itself to COMPLETED/FAILED via the existing
CompleteWorkflowService/FailWorkflowService (SPEC-ARO-004) rather than duplicating their
idempotency/versioning/outbox logic here.
"""

from __future__ import annotations

import dataclasses
import json
import uuid

from agentruntime.application.commands import CompleteAgentTaskCommand, CompleteWorkflowCommand, FailWorkflowCommand
from agentruntime.application.exceptions import (
    AgentTaskNotFoundException,
    ClaimTokenMismatchException,
    StalePauseGenerationException,
    StaleWorkflowVersionException,
    WorkflowInstanceNotFoundException,
    WorkflowInstanceVersionConflictException,
)
from agentruntime.application.ports_out import (
    AgentTaskRepository,
    CheckpointRepository,
    ClockPort,
    CommandIdempotencyRepository,
    OutboxRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import AgentTaskRecord, CheckpointRecord, OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import AgentTaskView
from agentruntime.domain import agent_task, checkpoint
from agentruntime.domain.enums import CheckpointType, WorkflowState
from agentruntime.domain.events import AgentTaskCompleted, AgentTaskDomainEvent, AgentTaskFailed
from agentruntime.domain.exceptions import InvalidWorkflowStateException
from agentruntime.domain.ids import CausationId, CheckpointId, CorrelationId, IdempotencyKey, WorkflowInstanceId

_EVENT_SCHEMA_VERSION = 1
_CHECKPOINT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "complete_agent_task"


class CompleteAgentTaskService:
    def __init__(
        self,
        agent_task_repository: AgentTaskRepository,
        workflow_instance_repository: WorkflowInstanceRepository,
        checkpoint_repository: CheckpointRepository,
        outbox_repository: OutboxRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
        coordinate_agent_tasks_service: CoordinateAgentTasksService,
        complete_workflow_service: CompleteWorkflowService,
        fail_workflow_service: FailWorkflowService,
    ) -> None:
        self._agent_task_repository = agent_task_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._checkpoint_repository = checkpoint_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._coordinate_agent_tasks_service = coordinate_agent_tasks_service
        self._complete_workflow_service = complete_workflow_service
        self._fail_workflow_service = fail_workflow_service
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def complete(self, command: CompleteAgentTaskCommand) -> AgentTaskView:
        request_payload = {
            "agentTaskId": str(command.agent_task_id), "claimToken": str(command.claim_token),
            "workflowVersion": command.workflow_version,
            "resultPayload": command.result_payload, "failureReason": command.failure_reason,
        }
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.agent_task_id), command.idempotency_key, request_payload,
            execute=lambda: self._complete(command),
            to_dict=lambda view: view.to_dict(), from_dict=AgentTaskView.from_dict,
        )

    def _complete(self, command: CompleteAgentTaskCommand) -> AgentTaskView:
        target = self._agent_task_repository.find_by_id(command.agent_task_id)
        if target is None:
            raise AgentTaskNotFoundException(str(command.agent_task_id))
        workflow = self._workflow_instance_repository.find_by_id(target.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(target.workflow_instance_id)

        if target.lease_token is None or target.lease_token != command.claim_token:
            raise ClaimTokenMismatchException()
        if command.workflow_version != workflow.workflow_version:
            raise StaleWorkflowVersionException()
        if target.pause_generation != workflow.pause_generation:
            raise StalePauseGenerationException()

        now = self._clock.now()

        if command.is_success:
            event = agent_task.complete(target.id, target.workflow_instance_id, target.state, target.task_version, command.result_payload, now)
            saved = self._agent_task_repository.save(self._with_outcome(target, event, command.result_payload, None, now))
            self._outbox_repository.append(self._to_outbox(workflow, "agent_runtime.agent_task.completed", event, now))
            self._after_task(saved, workflow, now)
            return AgentTaskView.from_record(saved, workflow_version=workflow.workflow_version)

        event = agent_task.fail(target.id, target.workflow_instance_id, target.state, target.task_version, command.failure_reason, now)
        saved = self._agent_task_repository.save(self._with_outcome(target, event, None, command.failure_reason, now))
        self._outbox_repository.append(self._to_outbox(workflow, "agent_runtime.agent_task.failed", event, now))
        self._after_task(saved, workflow, now)
        return AgentTaskView.from_record(saved, workflow_version=workflow.workflow_version)

    def _after_task(self, saved: AgentTaskRecord, workflow: WorkflowInstanceRecord, now) -> None:
        """SPEC-ARO-008 04-use-cases UC-02 steps 5-6, run for both outcomes: a failed
        attempt is still "task completion" in UC-02's broad sense, and
        unlock_downstream_tasks is a safe no-op when nothing newly satisfies its
        dependencies (a downstream task depending on a FAILED_FINAL predecessor never
        becomes runnable — domain.coordinator.runnable_task_keys only counts COMPLETED).
        SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6: once
        unlocking settles the whole graph, drive the Workflow Instance itself to its
        terminal state.
        """
        checkpoint_event = checkpoint.record(
            CheckpointId.new_id(), saved.workflow_instance_id, CheckpointType.AFTER_TASK, _CHECKPOINT_SCHEMA_VERSION,
            self._to_checkpoint_payload(saved), now,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version,
            payload=checkpoint_event.payload, recorded_at=checkpoint_event.occurred_at,
        ))
        self._coordinate_agent_tasks_service.unlock_downstream_tasks(saved.workflow_instance_id, workflow.state, now)
        self._settle_workflow_if_done(saved.workflow_instance_id, workflow.state)

    def _settle_workflow_if_done(self, workflow_instance_id: WorkflowInstanceId, current_workflow_state: WorkflowState) -> None:
        if current_workflow_state.is_terminal():
            return  # already settled by someone else (e.g. an admin override) — nothing to do

        settlement = self._coordinate_agent_tasks_service.determine_settlement(workflow_instance_id)
        if settlement is None:
            return

        # A deterministic key, not a caller-supplied one: this transition is system-
        # triggered by the task graph settling, not an external request. Deterministic
        # so a concurrent duplicate trigger (two sibling tasks completing at nearly the
        # same instant, both observing "settled") replays the same cached response
        # instead of attempting the transition twice.
        idempotency_key = IdempotencyKey(f"auto-settle:{workflow_instance_id}")
        try:
            if settlement is WorkflowState.COMPLETED:
                self._complete_workflow_service.complete(CompleteWorkflowCommand(workflow_instance_id, idempotency_key))
            else:
                self._fail_workflow_service.fail(FailWorkflowCommand(
                    workflow_instance_id, idempotency_key, "one or more agent tasks in the task graph did not complete successfully"
                ))
        except (WorkflowInstanceVersionConflictException, InvalidWorkflowStateException):
            # Another concurrently-completing sibling task already won this exact
            # transition (optimistic-version race) or the workflow reached a terminal
            # state some other way in the meantime — either way, the *task* this method
            # was called on already completed successfully; that must not be reported as
            # a failure just because this best-effort follow-up lost a race.
            pass

    def _to_checkpoint_payload(self, saved: AgentTaskRecord) -> str:
        return json.dumps({
            "agentTaskId": str(saved.id), "taskKey": saved.task_key, "toState": saved.state.name,
            "resultPayload": saved.result_payload, "failureReason": saved.failure_reason,
        })

    def _with_outcome(
        self, target: AgentTaskRecord, event: AgentTaskDomainEvent, result_payload: str | None, failure_reason: str | None, now
    ) -> AgentTaskRecord:
        return dataclasses.replace(
            target, state=event.to_state, task_version=event.task_version, result_payload=result_payload,
            failure_reason=failure_reason, updated_at=now,
        )

    def _to_outbox(self, workflow: WorkflowInstanceRecord, event_type: str, event: AgentTaskDomainEvent, now) -> OutboxRecord:
        payload: dict[str, object] = {
            "agentTaskId": str(event.agent_task_id),
            "workflowInstanceId": str(event.workflow_instance_id),
            "toState": event.to_state.name,
            "taskVersion": event.task_version,
            "occurredAt": event.occurred_at.isoformat(),
        }
        if isinstance(event, AgentTaskCompleted):
            payload["resultPayload"] = event.result_payload
        elif isinstance(event, AgentTaskFailed):
            payload["failureReason"] = event.failure_reason

        return OutboxRecord(
            outbox_id=uuid.uuid4(), workflow_instance_id=event.workflow_instance_id, ticket_id=workflow.ticket_id,
            correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), event_type=event_type,
            schema_version=_EVENT_SCHEMA_VERSION, payload=json.dumps(payload), occurred_at=now,
        )
