"""13-package-and-class-design §"Application Layer": ``cancel_tool_request.py``.
04-use-cases UC-TG-006 "Cancel Tool Request". 05-api-contracts §"Runtime API":
``POST /tool-requests/{toolRequestId}/cancel`` "Requires idempotencyKey and
requester" — a repeated cancel call for an already-CANCELLED (or any other
final — SPEC-TG-018 09-concurrency-and-idempotency §"Concurrent Cancellation":
"Completion commits first: cancel returns final completed") request is a
no-op returning the current fact, not an INVALID_STATE_TRANSITION error; no
dedicated idempotency-key ledger exists for this command (unlike ToolRequest
creation's own natural idempotency key) because the target/final state is
itself the idempotent fact to check against.

SPEC-TG-018 closed a real gap in the EXECUTING branch: SPEC-TG-001's own
walking skeleton confirmed CANCELLED immediately after calling the connector's
cancel hook, papering over 09-concurrency's own "Cancel commits first but
connector was called: request enters CANCEL_REQUESTED and waits for connector
hook/reconciliation" — a genuinely concurrent deployment (a worker's own
``execute_tool_request``/``reconcile_execution`` call resolving the same
attempt at the same time) could have its own outcome silently lost. This now
genuinely stops at CANCEL_REQUESTED; resolution is
``application.cancellation_race.save_resolved_tool_request``'s job, called
from whichever of those two services next resolves the attempt (or, if one is
already pending, from ``reconcile_execution``'s own explicit CANCEL_REQUESTED
branch — see that module's own docstring).
"""

from __future__ import annotations

import uuid

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.commands import CancelToolRequestCommand
from tool_gateway.application.exceptions import ToolRequestNotFoundException
from tool_gateway.application.outbox_events import build_cancelled_event
from tool_gateway.application.telemetry import ToolGatewayTelemetry
from tool_gateway.application.views import ToolRequestView
from tool_gateway.domain.enums import ToolRequestStatus
from tool_gateway.domain.errors import InvalidToolRequestTransitionException
from tool_gateway.domain.ids import ToolRequestId
from tool_gateway.domain.values import ConnectorInvocationSpec
from tool_gateway.ports.connector_port import ConnectorRegistryPort
from tool_gateway.ports.storage_port import (
    AuditRecordRepository,
    ClockPort,
    OutboxRepository,
    ToolExecutionRepository,
    ToolRequestRepository,
)


class CancelToolRequestService:
    def __init__(
        self, tool_request_repository: ToolRequestRepository, tool_execution_repository: ToolExecutionRepository,
        connector_registry_port: ConnectorRegistryPort, outbox_repository: OutboxRepository,
        audit_record_repository: AuditRecordRepository, clock: ClockPort, telemetry: ToolGatewayTelemetry,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._tool_execution_repository = tool_execution_repository
        self._connector_registry_port = connector_registry_port
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)
        self._telemetry = telemetry

    def cancel_tool_request(self, command: CancelToolRequestCommand) -> ToolRequestView:
        tool_request_id = ToolRequestId(uuid.UUID(command.tool_request_id))
        tool_request = self._tool_request_repository.find_by_id(tool_request_id)
        if tool_request is None:
            raise ToolRequestNotFoundException(command.tool_request_id)

        # Any final fact (COMPLETED/CANCELLED/TERMINAL_FAILED/REJECTED/
        # POLICY_DENIED/APPROVAL_DENIED) or an already-pending cancellation is
        # a no-op returning the current fact, not a conflict.
        if tool_request.status.is_terminal() or tool_request.status is ToolRequestStatus.CANCEL_REQUESTED:
            return ToolRequestView.from_domain(tool_request)

        now = self._clock.now()

        if tool_request.status is ToolRequestStatus.QUEUED:
            # 04-use-cases UC-TG-006 step 3: "If execution has not started,
            # ToolRequest enters CANCELLED."
            cancelled = tool_request.cancel_from_queue(now)
            saved = self._tool_request_repository.save(cancelled, expected_status=ToolRequestStatus.QUEUED)
            self._audit_recorder.record(
                action="execution_cancelled", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
                outcome=saved.status.name, actor_id=command.requested_by, correlation_id=command.correlation_id,
                detail=command.reason,
            )
            self._outbox_repository.append(build_cancelled_event(saved, command.reason, command.correlation_id, now))
            self._telemetry.record_request_completed("CANCELLED")
            return ToolRequestView.from_domain(saved)

        if tool_request.status is ToolRequestStatus.EXECUTING:
            # 04-use-cases UC-TG-006 step 4: "If executing, ToolRequest enters
            # CANCEL_REQUESTED and Gateway calls connector cancel hook." Stops
            # here — see this module's own docstring for why immediate
            # auto-confirmation was removed.
            active_execution = self._tool_execution_repository.find_active_by_tool_request(tool_request_id)
            if active_execution is not None:
                connector = self._connector_registry_port.find_by_id(active_execution.connector_id)
                if connector is not None:
                    adapter = self._connector_registry_port.get_adapter(active_execution.connector_id)
                    spec = ConnectorInvocationSpec(
                        connector_id=str(active_execution.connector_id), connector_version=active_execution.connector_version,
                        operation_key=str(active_execution.operation_key) if active_execution.operation_key else None,
                        input_payload={}, timeout_seconds=connector.timeout_policy.invoke_timeout_seconds,
                    )
                    adapter.cancel(spec)
            requested = tool_request.request_cancel_during_execution(now)
            saved = self._tool_request_repository.save(requested, expected_status=ToolRequestStatus.EXECUTING)
            self._audit_recorder.record(
                action="cancellation_requested", resource_type="TOOL_REQUEST", resource_id=str(tool_request_id),
                outcome=saved.status.name, actor_id=command.requested_by, correlation_id=command.correlation_id,
                detail=command.reason,
            )
            return ToolRequestView.from_domain(saved)

        raise InvalidToolRequestTransitionException(tool_request.status, ToolRequestStatus.CANCELLED)
