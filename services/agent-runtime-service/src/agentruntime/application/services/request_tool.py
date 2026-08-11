"""13-package-and-class-design §"Application Layer" / §"Class Boundaries":
RequestToolService is the only application service that may depend on
ToolGatewayPort (enforced by tests/architecture/test_tool_gateway_boundary.py) —
02-business-invariants §"Tool Gateway Boundary": "Agents cannot call Tools directly
... Runtime must centralize authorization, audit, rate limiting, and retry." Persists
a Checkpoint before ever dispatching (§"Checkpoint Invariants": "A checkpoint must
exist before any external side effect"). 09-concurrency-and-idempotency
§"Command Idempotency": "... Request Tool must include idempotencyKey" — without this,
a retried request would write a second Checkpoint and dispatch the tool twice.
"""

from __future__ import annotations

import dataclasses

from agentruntime.application.commands import RequestToolCommand
from agentruntime.application.ports_out import CheckpointRepository, ClockPort, CommandIdempotencyRepository, ToolGatewayPort, ToolRequestRepository
from agentruntime.application.records import CheckpointRecord, ToolRequestRecord
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import ToolRequestView
from agentruntime.domain import checkpoint, tool_request
from agentruntime.domain.enums import CheckpointType, ToolRequestStatus
from agentruntime.domain.ids import CheckpointId, ToolRequestId

_CHECKPOINT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "request_tool"


class RequestToolService:
    def __init__(
        self,
        checkpoint_repository: CheckpointRepository,
        tool_request_repository: ToolRequestRepository,
        tool_gateway_port: ToolGatewayPort,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
    ) -> None:
        self._checkpoint_repository = checkpoint_repository
        self._tool_request_repository = tool_request_repository
        self._tool_gateway_port = tool_gateway_port
        self._clock = clock
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
        now = self._clock.now()

        checkpoint_event = checkpoint.record(
            CheckpointId.new_id(), command.workflow_instance_id, CheckpointType.PRE_TOOL_CALL,
            _CHECKPOINT_SCHEMA_VERSION, command.checkpoint_payload, now,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version,
            payload=checkpoint_event.payload, recorded_at=checkpoint_event.occurred_at,
        ))

        tool_requested_event = tool_request.create(
            ToolRequestId.new_id(), command.workflow_instance_id, command.agent_task_id, checkpoint_event.checkpoint_id,
            command.tool_name, command.tool_request_payload, now,
        )
        pending = ToolRequestRecord(
            id=tool_requested_event.tool_request_id, workflow_instance_id=tool_requested_event.workflow_instance_id,
            agent_task_id=tool_requested_event.agent_task_id, preceding_checkpoint_id=tool_requested_event.preceding_checkpoint_id,
            tool_name=tool_requested_event.tool_name, request_payload=tool_requested_event.request_payload,
            status=ToolRequestStatus.PENDING, created_at=now, updated_at=now,
        )
        self._tool_request_repository.save(pending)

        acknowledgement = self._tool_gateway_port.dispatch(pending)

        dispatched = dataclasses.replace(pending, status=acknowledgement.status, updated_at=acknowledgement.acknowledged_at)
        saved = self._tool_request_repository.save(dispatched)

        return ToolRequestView.from_record(saved)
