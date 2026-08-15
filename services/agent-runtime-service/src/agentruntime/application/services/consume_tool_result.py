"""13-package-and-class-design §"Application Layer": ConsumeToolResultService.
SPEC-ARO-020 04-use-cases UC-04 "消费 tool.completed":
1. Consumer 收到 tool.completed — handled by ConsumeRuntimeEventService's own
   dedup/staleness gate before this service is ever reached.
2. "根据 gatewayCorrelationId 或 toolRequestId 找到 Tool Request" — this service looks the
   Tool Request up by toolRequestId (06-event-contracts §"tool.completed.v1": "必须匹配
   Runtime 已持久化的 Tool Request").
3. "校验 Tool Request 状态仍等待结果" — a Tool Request already COMPLETED/FAILED is a no-op,
   not an error: a duplicate delivery under a different eventId (past the processed_events
   dedup) must not re-apply an outcome that already landed.
4. "保存 tool result 到 Tool Request 和 checkpoint" — reuses CheckpointType.AFTER_TASK:
   03-state-machine's own checkpoint-type list has no distinct "after tool result" type,
   and by the time this runs the Agent Task really has reached its terminal outcome for
   this attempt, tool-driven or not — the same semantic AFTER_TASK already carries for
   CompleteAgentTaskService.
5. "更新对应 Agent Task" — wakes the Workflow Instance (WAITING_FOR_TOOL -> RUNNING, the
   counterpart to SPEC-ARO-019's wait_for_tool()) and moves the Agent Task from
   WAITING_TOOL straight to COMPLETED or FAILED_FINAL via the new
   domain.agent_task.complete_from_tool_result()/fail_from_tool_result() — a status field
   on the *same* tool.completed.v1 event distinguishes the two outcomes; 06-event-contracts
   does not document a separate tool.failed.v1 contract.
6. "发布 agent.task.completed 或创建后续 task" — publishes agent_runtime.agent_task.
   completed/.failed (the same outbox event types CompleteAgentTaskService already
   publishes) and reuses CoordinateAgentTasksService.unlock_downstream_tasks/
   determine_settlement for downstream unlocking and auto-complete/auto-fail. "创建后续
   task" (spawning a follow-up task from a tool result) is deliberately not implemented:
   nothing in this codebase decides *when* a follow-up task should exist versus the tool
   result simply completing the task outright, and inventing that policy now would be
   speculative design this spec's own mapped LLD sections (04-use-cases, 06-event-
   contracts) do not ask for.
"""

from __future__ import annotations

import dataclasses
import json
import logging
import uuid

from agentruntime.application.commands import CompleteWorkflowCommand, FailWorkflowCommand
from agentruntime.application.exceptions import (
    AgentTaskNotFoundException,
    ToolRequestNotFoundException,
    WorkflowInstanceNotFoundException,
    WorkflowInstanceVersionConflictException,
)
from agentruntime.application.ports_out import (
    AgentTaskRepository,
    CheckpointRepository,
    ClockPort,
    OutboxRepository,
    ToolRequestRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import AgentTaskRecord, CheckpointRecord, OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain import agent_task, checkpoint, workflow_instance
from agentruntime.domain.enums import CheckpointType, ToolRequestStatus, WorkflowState
from agentruntime.domain.events import AgentTaskCompleted, AgentTaskDomainEvent, AgentTaskFailed
from agentruntime.domain.exceptions import InvalidWorkflowStateException
from agentruntime.domain.ids import CausationId, CheckpointId, CorrelationId, IdempotencyKey, ToolRequestId, WorkflowInstanceId

logger = logging.getLogger(__name__)

_EVENT_SCHEMA_VERSION = 1
_CHECKPOINT_SCHEMA_VERSION = 1
_STILL_WAITING_TOOL_REQUEST_STATUSES = frozenset({ToolRequestStatus.PENDING, ToolRequestStatus.DISPATCHED})

# SPEC-ARO-026 06-event-contracts §"agent.task.completed.v1": see
# complete_agent_task.py's own identical constants for the full renaming rationale — a
# tool-driven task outcome publishes the exact same event family a worker-reported one
# does.
_EVENT_TYPE_COMPLETED = "agent.task.completed.v1"
_EVENT_TYPE_FAILED = "agent.task.failed.v1"


class ConsumeToolResultService:
    def __init__(
        self,
        tool_request_repository: ToolRequestRepository,
        agent_task_repository: AgentTaskRepository,
        workflow_instance_repository: WorkflowInstanceRepository,
        checkpoint_repository: CheckpointRepository,
        outbox_repository: OutboxRepository,
        clock: ClockPort,
        coordinate_agent_tasks_service: CoordinateAgentTasksService,
        complete_workflow_service: CompleteWorkflowService,
        fail_workflow_service: FailWorkflowService,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._agent_task_repository = agent_task_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._checkpoint_repository = checkpoint_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._coordinate_agent_tasks_service = coordinate_agent_tasks_service
        self._complete_workflow_service = complete_workflow_service
        self._fail_workflow_service = fail_workflow_service

    def apply(self, payload_json: str) -> None:
        payload = json.loads(payload_json)
        tool_request_id = ToolRequestId(uuid.UUID(payload["toolRequestId"]))
        succeeded = payload["status"] == "COMPLETED"
        result_payload: str | None = payload.get("resultPayload")

        tool_request = self._tool_request_repository.find_by_id(tool_request_id)
        if tool_request is None:
            raise ToolRequestNotFoundException(str(tool_request_id))
        if tool_request.status not in _STILL_WAITING_TOOL_REQUEST_STATUSES:
            return  # UC-04 step 3: already resolved — a duplicate delivery, not an error

        now = self._clock.now()
        new_status = ToolRequestStatus.COMPLETED if succeeded else ToolRequestStatus.FAILED
        self._tool_request_repository.save(dataclasses.replace(
            tool_request, status=new_status, result_payload=result_payload, updated_at=now
        ))

        target = self._agent_task_repository.find_by_id(tool_request.agent_task_id)
        if target is None:
            raise AgentTaskNotFoundException(str(tool_request.agent_task_id))
        workflow = self._workflow_instance_repository.find_by_id(target.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(target.workflow_instance_id)

        wake_event = workflow_instance.wake_from_tool_wait(workflow.id, workflow.state, workflow.workflow_version, now)
        awake_workflow = self._workflow_instance_repository.save(dataclasses.replace(
            workflow, state=wake_event.to_state, workflow_version=wake_event.workflow_version, updated_at=now
        ))

        if succeeded:
            task_event = agent_task.complete_from_tool_result(
                target.id, target.workflow_instance_id, target.state, target.task_version, result_payload or "", now
            )
            saved = self._agent_task_repository.save(self._with_outcome(target, task_event, result_payload, None, now))
            outbox_record = self._to_outbox(awake_workflow, _EVENT_TYPE_COMPLETED, task_event, now)
        else:
            failure_reason = result_payload or "tool request failed"
            task_event = agent_task.fail_from_tool_result(
                target.id, target.workflow_instance_id, target.state, target.task_version, failure_reason, now
            )
            saved = self._agent_task_repository.save(self._with_outcome(target, task_event, None, failure_reason, now))
            outbox_record = self._to_outbox(awake_workflow, _EVENT_TYPE_FAILED, task_event, now)

        self._outbox_repository.append(outbox_record)
        self._log_agent_task_event(saved, awake_workflow, outbox_record)
        self._after_task(saved, awake_workflow, now)

    def _after_task(self, saved: AgentTaskRecord, workflow: WorkflowInstanceRecord, now) -> None:
        """Mirrors CompleteAgentTaskService._after_task(): write the AFTER_TASK
        checkpoint, unlock whatever just became runnable, and settle the workflow if the
        task graph is now done.
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
            return

        settlement = self._coordinate_agent_tasks_service.determine_settlement(workflow_instance_id)
        if settlement is None:
            return

        idempotency_key = IdempotencyKey(f"auto-settle:{workflow_instance_id}")
        try:
            if settlement is WorkflowState.COMPLETED:
                self._complete_workflow_service.complete(CompleteWorkflowCommand(workflow_instance_id, idempotency_key))
            else:
                self._fail_workflow_service.fail(FailWorkflowCommand(
                    workflow_instance_id, idempotency_key, "one or more agent tasks in the task graph did not complete successfully"
                ))
        except (WorkflowInstanceVersionConflictException, InvalidWorkflowStateException):
            pass

    def _log_agent_task_event(self, saved: AgentTaskRecord, workflow: WorkflowInstanceRecord, outbox_record: OutboxRecord) -> None:
        """SPEC-ARO-026 12-observability §"日志": mirrors CompleteAgentTaskService's own
        identical helper — a tool-driven outcome carries the same required log fields a
        worker-reported one does.
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
