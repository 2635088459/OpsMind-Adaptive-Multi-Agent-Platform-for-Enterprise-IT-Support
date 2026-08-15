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
idempotency/versioning/outbox logic here. SPEC-ARO-016 (Stale Generation Worker Result):
a submission rejected for a stale workflowVersion/pauseGeneration now also persists the
task as STALE (domain.agent_task.mark_stale()) before the rejection is raised — previously
the task record was simply left untouched in CLAIMED/RUNNING limbo, which the
AgentTaskState.STALE enum member's own docstring flagged as this spec's unfinished half
("persisting that outcome as this state" — the other half, the path back to claimable,
already existed via is_claimable()/domain.agent_task.claim()).
"""

from __future__ import annotations

import dataclasses
import json
import logging
import uuid

from opentelemetry import trace

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
from agentruntime.application.redaction import redact_payload
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import AgentTaskView
from agentruntime.domain import agent_task, checkpoint
from agentruntime.domain.enums import CheckpointType, WorkflowState
from agentruntime.domain.events import AgentTaskCompleted, AgentTaskDomainEvent, AgentTaskFailed
from agentruntime.domain.exceptions import InvalidAgentTaskTransitionException, InvalidWorkflowStateException
from agentruntime.domain.ids import CausationId, CheckpointId, CorrelationId, IdempotencyKey, WorkflowInstanceId

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

_EVENT_SCHEMA_VERSION = 1
_CHECKPOINT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "complete_agent_task"

# SPEC-ARO-026 06-event-contracts §"agent.task.completed.v1": the external contract
# name, not the internal "agent_runtime.agent_task.*" prefix SPEC-ARO-008/010 originally
# used (same rename SPEC-ARO-025 already applied to the workflow lifecycle events).
# 06-event-contracts documents only the completed case explicitly, but names it
# "agent.task.completed.v1" — not a bare "task.completed" — so the failed sibling this
# codebase already publishes as a genuinely separate outbox event (not a status field on
# one type, since AgentTaskCompleted/AgentTaskFailed are already distinct domain events)
# follows the same "agent.task.*" namespace for consistency, mirroring how workflow.
# completed.v1/workflow.failed.v1 are both documented as separate types.
_EVENT_TYPE_COMPLETED = "agent.task.completed.v1"
_EVENT_TYPE_FAILED = "agent.task.failed.v1"


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
        telemetry: RuntimeTelemetry,
        audit_recorder: AuditRecorder,
    ) -> None:
        self._agent_task_repository = agent_task_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._checkpoint_repository = checkpoint_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._coordinate_agent_tasks_service = coordinate_agent_tasks_service
        self._complete_workflow_service = complete_workflow_service
        self._fail_workflow_service = fail_workflow_service
        self._telemetry = telemetry
        self._audit_recorder = audit_recorder
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
        with tracer.start_as_current_span("task.completion"):
            return self._complete_traced(command)

    def _complete_traced(self, command: CompleteAgentTaskCommand) -> AgentTaskView:
        target = self._agent_task_repository.find_by_id(command.agent_task_id)
        if target is None:
            raise AgentTaskNotFoundException(str(command.agent_task_id))
        workflow = self._workflow_instance_repository.find_by_id(target.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(target.workflow_instance_id)

        now = self._clock.now()

        if target.lease_token is None or target.lease_token != command.claim_token:
            raise ClaimTokenMismatchException()
        if command.workflow_version != workflow.workflow_version:
            self._mark_stale(target, "stale_workflow_version", now)
            raise StaleWorkflowVersionException()
        if target.pause_generation != workflow.pause_generation:
            self._mark_stale(target, "stale_pause_generation", now)
            raise StalePauseGenerationException()

        duration_seconds = (now - target.created_at).total_seconds()

        if command.is_success:
            event = agent_task.complete(target.id, target.workflow_instance_id, target.state, target.task_version, command.result_payload, now)
            saved = self._agent_task_repository.save(self._with_outcome(target, event, command.result_payload, None, now))
            outbox_record = self._to_outbox(workflow, _EVENT_TYPE_COMPLETED, event, now)
            self._outbox_repository.append(outbox_record)
            self._log_agent_task_event(saved, workflow, outbox_record)
            self._telemetry.record_task_completed(duration_seconds)
            self._audit_recorder.record(
                "TASK_TRANSITION", "complete_task", "AgentTask", str(saved.id), "SUCCESS",
                workflow_instance_id=saved.workflow_instance_id, ticket_id=workflow.ticket_id, actor_type="WORKER",
                actor_id=saved.worker_id, correlation_id=str(outbox_record.correlation_id), causation_id=str(outbox_record.causation_id),
            )
            self._after_task(saved, workflow, now)
            return AgentTaskView.from_record(saved, workflow_version=workflow.workflow_version)

        event = agent_task.fail(target.id, target.workflow_instance_id, target.state, target.task_version, command.failure_reason, now)
        saved = self._agent_task_repository.save(self._with_outcome(target, event, None, command.failure_reason, now))
        outbox_record = self._to_outbox(workflow, _EVENT_TYPE_FAILED, event, now)
        self._outbox_repository.append(outbox_record)
        self._log_agent_task_event(saved, workflow, outbox_record)
        self._telemetry.record_task_failed(duration_seconds)
        self._audit_recorder.record(
            "TASK_TRANSITION", "fail_task", "AgentTask", str(saved.id), "FAILURE",
            workflow_instance_id=saved.workflow_instance_id, ticket_id=workflow.ticket_id, actor_type="WORKER",
            actor_id=saved.worker_id, correlation_id=str(outbox_record.correlation_id), causation_id=str(outbox_record.causation_id),
            detail=redact_payload(json.dumps({"failure_reason": command.failure_reason})),
        )
        self._after_task(saved, workflow, now)
        return AgentTaskView.from_record(saved, workflow_version=workflow.workflow_version)

    def _mark_stale(self, target: AgentTaskRecord, reason: str, now) -> None:
        """SPEC-ARO-016 (Stale Generation Worker Result): persists the rejection this
        method is called ahead of, rather than leaving the task silently sitting in
        CLAIMED/RUNNING forever. A no-op if the task is not (or no longer) actively
        claimed — e.g. it already reached a terminal state, or another concurrent stale
        submission already marked it STALE — since an out-of-date worker result must
        never overwrite whatever legitimate outcome already landed.

        Also clears lease_expires_at: domain.agent_task.claim()'s AlreadyClaimed guard
        only blocks reclaiming a non-READY task while its lease is still unexpired, so
        leaving the old lease in place would make a STALE task wait out the *original*
        worker's full lease duration before anyone could pick it up — the wrong outcome,
        since going stale is exactly proof that worker's claim can no longer be trusted.
        worker_id/lease_token are left as-is, an audit trail of who held the claim right
        before it went stale.
        """
        try:
            event = agent_task.mark_stale(target.id, target.workflow_instance_id, target.state, target.task_version, reason, now)
        except InvalidAgentTaskTransitionException:
            return
        self._agent_task_repository.save(dataclasses.replace(
            target, state=event.to_state, task_version=event.task_version, lease_expires_at=None, updated_at=now
        ))
        self._audit_recorder.record(
            "TASK_TRANSITION", "mark_stale", "AgentTask", str(target.id), "SUCCESS",
            workflow_instance_id=target.workflow_instance_id, actor_type="WORKER", actor_id=target.worker_id,
            detail=json.dumps({"reason": reason}),
        )

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
            workflow_version=workflow.workflow_version,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version,
            payload=checkpoint_event.payload, recorded_at=checkpoint_event.occurred_at,
            workflow_version=checkpoint_event.workflow_version, checksum=checkpoint_event.checksum, cursor=checkpoint_event.cursor,
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

    def _log_agent_task_event(self, saved: AgentTaskRecord, workflow: WorkflowInstanceRecord, outbox_record: OutboxRecord) -> None:
        """SPEC-ARO-026 12-observability §"日志": "所有 Runtime 日志必须带 workflowInstanceId,
        ticketId, ticketCycleId, agentTaskId (如果存在), correlationId, causationId,
        workerId (如果存在)." Reuses the same correlation_id/causation_id the outbox
        record itself was just published under, so a log line and its corresponding
        broker message are trivially correlatable by a human reading both.
        """
        logger.info(
            "agent task event published event_type=%s workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "agent_task_id=%s worker_id=%s correlation_id=%s causation_id=%s",
            outbox_record.event_type, workflow.id, workflow.ticket_id, workflow.ticket_cycle_id,
            saved.id, saved.worker_id, outbox_record.correlation_id, outbox_record.causation_id,
        )

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
