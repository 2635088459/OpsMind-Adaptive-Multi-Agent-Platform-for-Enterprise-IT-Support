"""13-package-and-class-design §"Application Layer": ``create_tool_request.py``.
04-use-cases UC-TG-001 "Runtime Submits Tool Request", steps 1-4: receive,
validate (schema/actor/capability/idempotency), persist, publish
``tool.request.accepted.v1`` — or persist REJECTED and return that fact instead
of publishing. Stops at VALIDATING (or REJECTED); the risk-decision step
(POLICY_CHECKING onward) is ``evaluate_tool_request``'s job, matching the
LLD's own file-per-use-case-slice split.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import datetime

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.commands import CreateToolRequestCommand
from tool_gateway.application.exceptions import ToolRequestIdempotencyConflictException, ToolRequestNotFoundException
from tool_gateway.application.telemetry import ToolGatewayTelemetry
from tool_gateway.application.views import ToolRequestView
from tool_gateway.domain.enums import RequestedByType
from tool_gateway.domain.ids import AgentTaskId, IdempotencyKey, TicketCycleId, TicketId, ToolRequestId, WorkflowInstanceId
from tool_gateway.domain.records import OutboxRecord
from tool_gateway.domain.tool_request import ToolRequest
from tool_gateway.ports.connector_port import ConnectorRegistryPort
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort, OutboxRepository, ToolRequestRepository


def _compute_payload_hash(capability_name: str, input_payload: dict, reason: str) -> str:
    """07-data-model `tool_requests.payload_hash`; 09-concurrency-and-idempotency
    §"Tool Request Idempotency". ``sort_keys=True`` makes the hash independent of
    dict insertion order, so semantically-identical retries always hash equal.
    """

    canonical = json.dumps({"capabilityName": capability_name, "inputPayload": input_payload, "reason": reason}, sort_keys=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _parse_optional_uuid(value: str | None) -> uuid.UUID | None:
    return uuid.UUID(value) if value is not None else None


class CreateToolRequestService:
    def __init__(
        self, tool_request_repository: ToolRequestRepository, connector_registry_port: ConnectorRegistryPort,
        outbox_repository: OutboxRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
        telemetry: ToolGatewayTelemetry,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._connector_registry_port = connector_registry_port
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)
        self._telemetry = telemetry

    def create_tool_request(self, command: CreateToolRequestCommand) -> ToolRequestView:
        idempotency_key = IdempotencyKey(command.idempotency_key)
        payload_hash = _compute_payload_hash(command.capability_name, command.input_payload, command.reason)

        # 04-use-cases UC-TG-001 step 2 ("Gateway validates ... idempotency") +
        # Reliability Acceptance: "Duplicate requests ... do not create duplicate
        # external side effects." 09-concurrency-and-idempotency §"Tool Request
        # Idempotency": a replay with the SAME payload hash returns the
        # previously persisted fact; a DIFFERENT payload hash under the same key
        # is a real conflict, not a replay, and must be rejected rather than
        # silently executed under someone else's idempotency key.
        existing = self._tool_request_repository.find_by_idempotency_key(
            command.workflow_instance_id, command.agent_task_id, idempotency_key,
        )
        if existing is not None:
            if existing.payload_hash != payload_hash:
                raise ToolRequestIdempotencyConflictException(command.idempotency_key)
            return ToolRequestView.from_domain(existing)

        now = self._clock.now()
        tool_request = ToolRequest.submit(
            tool_request_id=ToolRequestId.new_id(), idempotency_key=idempotency_key, payload_hash=payload_hash,
            requested_by_type=RequestedByType[command.requested_by_type], requested_by_id=command.requested_by_id,
            capability_name=command.capability_name, input_payload=command.input_payload, reason=command.reason,
            submitted_at=now, tool_name=command.tool_name,
            ticket_id=TicketId(_parse_optional_uuid(command.ticket_id)) if command.ticket_id else None,
            ticket_cycle_id=TicketCycleId(_parse_optional_uuid(command.ticket_cycle_id)) if command.ticket_cycle_id else None,
            workflow_instance_id=(
                WorkflowInstanceId(_parse_optional_uuid(command.workflow_instance_id)) if command.workflow_instance_id else None
            ),
            agent_task_id=AgentTaskId(_parse_optional_uuid(command.agent_task_id)) if command.agent_task_id else None,
        )
        tool_request = tool_request.begin_validation(now)

        connector = self._connector_registry_port.find_by_capability(command.capability_name)
        if connector is None:
            return self._reject(tool_request, f"capability '{command.capability_name}' has no registered connector", now, command)

        # SPEC-TG-021 INV-TG-009: "Runtime visibility of a capability does not
        # mean an Agent may execute it." A connector may restrict which
        # requester types (AGENT/SYSTEM/HUMAN_OPERATOR) may invoke it at all —
        # 06-event-contracts §"tool.request.rejected.v1": "Published when
        # request is rejected due to schema, capability, permission, or
        # idempotency conflict" names "permission" explicitly as a rejection
        # cause, which this is the first real instance of.
        if not connector.is_requester_allowed(RequestedByType[command.requested_by_type]):
            return self._reject(
                tool_request, f"requester type '{command.requested_by_type}' is not permitted for this capability", now, command,
            )

        # INV-TG-008: bind the resolved connector's id+version now, at intake —
        # execute_tool_request reuses this exact binding rather than
        # re-resolving by capability name, so a connector upgrade registered
        # between accept and execute cannot silently swap in a different
        # schema/version than what was actually validated here.
        tool_request = tool_request.bind_connector(connector.connector_id, connector.version, now)

        saved = self._tool_request_repository.save(tool_request, expected_status=None)
        self._audit_recorder.record(
            action="request_accepted", resource_type="TOOL_REQUEST", resource_id=str(saved.tool_request_id),
            outcome="ACCEPTED", actor_id=command.requested_by_id, correlation_id=command.correlation_id,
            ticket_id=command.ticket_id,
        )
        self._outbox_repository.append(OutboxRecord(
            outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(saved.tool_request_id),
            event_type="tool.request.accepted.v1", event_version="1.0",
            payload={"toolRequestId": str(saved.tool_request_id), "capabilityName": saved.capability_name, "status": saved.status.name},
            occurred_at=now, correlation_id=command.correlation_id,
        ))
        self._telemetry.record_request_created()
        return ToolRequestView.from_domain(saved)

    def _reject(self, tool_request: ToolRequest, reason: str, now: datetime, command: CreateToolRequestCommand) -> ToolRequestView:
        """Shared REJECTED-path tail — capability-not-found (already existed)
        and SPEC-TG-021's own requester-type-not-allowed both land here rather
        than duplicating the reject/audit/publish sequence.
        """

        rejected = tool_request.reject(reason, now)
        saved = self._tool_request_repository.save(rejected, expected_status=None)
        self._audit_recorder.record(
            action="request_rejected", resource_type="TOOL_REQUEST", resource_id=str(saved.tool_request_id),
            outcome="REJECTED", actor_id=command.requested_by_id, correlation_id=command.correlation_id,
            ticket_id=command.ticket_id, detail=saved.denial_reason,
        )
        self._outbox_repository.append(OutboxRecord(
            outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(saved.tool_request_id),
            event_type="tool.request.rejected.v1", event_version="1.0",
            payload={
                "toolRequestId": str(saved.tool_request_id), "capabilityName": saved.capability_name,
                "reason": saved.denial_reason, "status": saved.status.name,
                # SPEC-TG-025 "02 Ticket Workflow Traceability Contract": a
                # rejected fact with no ticket/workflow context is untraceable
                # to whatever raised it — added alongside tool.completed.v1's
                # own already-complete field set (SPEC-TG-015).
                "ticketId": str(saved.ticket_id) if saved.ticket_id else None,
                "ticketCycleId": str(saved.ticket_cycle_id) if saved.ticket_cycle_id else None,
                "workflowInstanceId": str(saved.workflow_instance_id) if saved.workflow_instance_id else None,
                "agentTaskId": str(saved.agent_task_id) if saved.agent_task_id else None,
            },
            occurred_at=now, correlation_id=command.correlation_id,
        ))
        self._telemetry.record_request_completed("REJECTED")
        return ToolRequestView.from_domain(saved)

    def find_tool_request(self, tool_request_id: str) -> ToolRequestView:
        tool_request = self._tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(tool_request_id)))
        if tool_request is None:
            raise ToolRequestNotFoundException(tool_request_id)
        return ToolRequestView.from_domain(tool_request)
