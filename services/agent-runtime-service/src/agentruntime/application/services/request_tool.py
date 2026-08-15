"""13-package-and-class-design §"Application Layer" / §"Class Boundaries".
02-business-invariants §"Tool Gateway Boundary": "Agents cannot call Tools directly
... Runtime must centralize authorization, audit, rate limiting, and retry." Persists
a Checkpoint before ever dispatching (§"Checkpoint Invariants": "A checkpoint must
exist before any external side effect"). 09-concurrency-and-idempotency
§"Command Idempotency": "... Request Tool must include idempotencyKey" — without this,
a retried request would write a second Checkpoint twice.
SPEC-ARO-011 01-domain-model: the Checkpoint written here must carry the owning
Workflow Instance's workflow_version (one of Checkpoint's own minimal fields), so this
service also reads WorkflowInstanceRepository purely to look that value up.
SPEC-ARO-017 01-domain-model: the ToolRequestRecord this service creates carries its
own idempotency_key straight from the command and an optional caller-declared
capability — completing Tool Request's field shape.
SPEC-ARO-018 02-business-invariants §"Tool Gateway Boundary" / 11-security
§"Authorization": "claim/complete task：受信 worker identity" — validates claimToken
against the Agent Task's own lease before writing the checkpoint or persisting
anything, mirroring CompleteAgentTaskService's own check.

SPEC-ARO-032 11-security §"Tool Gateway 强制路径"/§"Authorization" adds the capability
check: if the command declares a capability, it must be one CapabilityPolicyPort
authorizes for the claiming Agent Task's own agent_role — checked immediately after
the claimToken check and before the checkpoint is ever written, the same "authorize
before any side effect" ordering claimToken validation already established. A capability
is a no-op pass-through (not denied) when agent_role is absent: SPEC-ARO-007's own
"no Planner capability assigns [agent_role] yet" deferral is a separate, still-pending
gap this spec does not close, and almost no live Tool Request carries a role to check
against yet — treating an absent role as an implicit denial would block essentially
every current caller for an unrelated reason, not enforce anything meaningful.

SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction": this service is no
longer the one that calls ToolGatewayPort — step 6's "Tool Gateway 调用不能在事务内直接
同步执行" ("the Tool Gateway call must not execute synchronously inside the
transaction") means this method's job ends once a PENDING Tool Request is durably
persisted (step 3), the owning Agent Task is set to WAITING_TOOL (step 4), and the
Workflow Instance is set to WAITING_FOR_TOOL (step 5). There is no separate "outbox
command" row to insert for step 6: 07-data-model's own `tool_requests` table has no
outbox-shaped columns (available_at/published_at) distinct from `state` — the
persisted PENDING record itself *is* the durable, decoupled unit of work
DispatchToolRequestsService (the new, sole holder of ToolGatewayPort — see
tests/architecture/test_tool_gateway_boundary.py) later scans for and dispatches,
outside of and after this transaction. Waking WAITING_TOOL/WAITING_FOR_TOOL back up is
SPEC-ARO-020's job (consuming tool.completed/tool.failed), not this service's — after
a successful call here, the claiming worker can no longer complete this task directly
(domain.agent_task._require_active_claim() only accepts CLAIMED/RUNNING, not
WAITING_TOOL), by design: 02-business-invariants requires "Tool result 必须通过
tool.completed 或 tool.failed 回到 Runtime," not a worker self-reporting completion
after handing a task off to a tool.
"""

from __future__ import annotations

import dataclasses
import json
import logging

from opentelemetry import trace

from agentruntime.application.commands import RequestToolCommand
from agentruntime.application.exceptions import (
    AgentTaskNotFoundException,
    CapabilityNotAuthorizedException,
    ClaimTokenMismatchException,
    WorkflowInstanceNotFoundException,
)
from agentruntime.application.ports_out import (
    AgentTaskRepository,
    CapabilityPolicyPort,
    CheckpointRepository,
    ClockPort,
    CommandIdempotencyRepository,
    ToolRequestRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import CheckpointRecord, ToolRequestRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import ToolRequestView
from agentruntime.domain import agent_task, checkpoint, tool_request, workflow_instance
from agentruntime.domain.enums import CheckpointType, ToolRequestStatus
from agentruntime.domain.ids import CheckpointId, ToolRequestId

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

_CHECKPOINT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "request_tool"


class RequestToolService:
    def __init__(
        self,
        checkpoint_repository: CheckpointRepository,
        tool_request_repository: ToolRequestRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
        workflow_instance_repository: WorkflowInstanceRepository,
        agent_task_repository: AgentTaskRepository,
        capability_policy_port: CapabilityPolicyPort,
        audit_recorder: AuditRecorder,
    ) -> None:
        self._checkpoint_repository = checkpoint_repository
        self._tool_request_repository = tool_request_repository
        self._clock = clock
        self._workflow_instance_repository = workflow_instance_repository
        self._agent_task_repository = agent_task_repository
        self._capability_policy_port = capability_policy_port
        self._audit_recorder = audit_recorder
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def request_tool(self, command: RequestToolCommand) -> ToolRequestView:
        request_payload = {
            "workflowInstanceId": str(command.workflow_instance_id), "agentTaskId": str(command.agent_task_id),
            "toolName": command.tool_name, "toolRequestPayload": command.tool_request_payload,
        }
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.agent_task_id), command.idempotency_key, request_payload,
            execute=lambda: self._request_tool(command),
            to_dict=lambda view: view.to_dict(), from_dict=ToolRequestView.from_dict,
        )

    def _request_tool(self, command: RequestToolCommand) -> ToolRequestView:
        with tracer.start_as_current_span("tool.request"):
            return self._request_tool_traced(command)

    def _request_tool_traced(self, command: RequestToolCommand) -> ToolRequestView:
        now = self._clock.now()

        workflow = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

        target = self._agent_task_repository.find_by_id(command.agent_task_id)
        if target is None:
            raise AgentTaskNotFoundException(str(command.agent_task_id))
        if target.lease_token is None or target.lease_token != command.claim_token:
            raise ClaimTokenMismatchException()
        if command.capability is not None and target.agent_role is not None:
            if not self._capability_policy_port.is_authorized(target.agent_role, command.capability):
                raise CapabilityNotAuthorizedException(target.agent_role, command.capability)

        checkpoint_event = checkpoint.record(
            CheckpointId.new_id(), command.workflow_instance_id, CheckpointType.PRE_TOOL_CALL,
            _CHECKPOINT_SCHEMA_VERSION, command.checkpoint_payload, now,
            workflow_version=workflow.workflow_version,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version,
            payload=checkpoint_event.payload, recorded_at=checkpoint_event.occurred_at,
            workflow_version=checkpoint_event.workflow_version, checksum=checkpoint_event.checksum, cursor=checkpoint_event.cursor,
        ))

        tool_requested_event = tool_request.create(
            ToolRequestId.new_id(), command.workflow_instance_id, command.agent_task_id, checkpoint_event.checkpoint_id,
            command.tool_name, command.tool_request_payload, now, command.capability,
        )
        pending = ToolRequestRecord(
            id=tool_requested_event.tool_request_id, workflow_instance_id=tool_requested_event.workflow_instance_id,
            agent_task_id=tool_requested_event.agent_task_id, preceding_checkpoint_id=tool_requested_event.preceding_checkpoint_id,
            tool_name=tool_requested_event.tool_name, request_payload=tool_requested_event.request_payload,
            status=ToolRequestStatus.PENDING, created_at=now, updated_at=now,
            capability=tool_requested_event.capability, idempotency_key=str(command.idempotency_key),
        )
        saved = self._tool_request_repository.save(pending)

        task_event = agent_task.wait_for_tool(target.id, target.workflow_instance_id, target.state, target.task_version, now)
        self._agent_task_repository.save(dataclasses.replace(
            target, state=task_event.to_state, task_version=task_event.task_version, updated_at=now
        ))

        workflow_event = workflow_instance.wait_for_tool(workflow.id, workflow.state, workflow.workflow_version, now)
        self._workflow_instance_repository.save(dataclasses.replace(
            workflow, state=workflow_event.to_state, workflow_version=workflow_event.workflow_version, updated_at=now
        ))

        logger.info(
            "action=request_tool status=completed workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "agent_task_id=%s tool_name=%s",
            workflow.id, workflow.ticket_id, workflow.ticket_cycle_id, target.id, command.tool_name,
        )
        self._audit_recorder.record(
            "TOOL_REQUEST_CREATED", "request_tool", "ToolRequest", str(saved.id), "SUCCESS",
            workflow_instance_id=workflow.id, ticket_id=workflow.ticket_id, actor_type="WORKER", actor_id=target.worker_id,
            detail=json.dumps({"tool_name": command.tool_name}),
        )
        return ToolRequestView.from_record(saved)
