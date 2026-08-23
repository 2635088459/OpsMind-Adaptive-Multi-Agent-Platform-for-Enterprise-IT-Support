"""Shared outbox-event payload builders used by more than one application
service — kept out of any single service module so
``evaluate_tool_request``/``approve_tool_request``/``execute_tool_request``/
``reconcile_execution`` (every one of which can reach a ``tool.completed.v1``
outcome) build the exact same shape rather than independently-drifting
copies. 06-event-contracts §"Published Events" §"tool.completed.v1" is the
payload shape transcribed below field-for-field — SPEC-TG-001's own original
success-path publish (``execute_tool_request.py``) only ever carried
``toolRequestId``/``executionId``/``status``/``summary``, silently dropping
every other contractual field (``ticketId``, ``capabilityName``,
``connectorId``, ``structuredOutput``, ``resultEnvelopeId``, ``evidenceRefs``,
``redactionStatus``, ``errorCode``, ``retryable``) until SPEC-TG-015 caught it
by diffing the wired payload against this LLD section literally.

``status`` uses ``_EVENT_STATUS_NAMES`` to translate the internal
``domain.enums.ResultStatus.SUCCESS`` name to the wire value 06-event-
contracts' own example shows (``"SUCCEEDED"``) — the internal enum member name
itself is not renamed (that would ripple through persistence/tests for a
purely cosmetic wire-casing difference); only the outbox-payload boundary
translates it.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from tool_gateway.domain.connector import ToolConnector
from tool_gateway.domain.enums import ResultStatus
from tool_gateway.domain.records import OutboxRecord
from tool_gateway.domain.result_envelope import ToolResultEnvelope
from tool_gateway.domain.tool_execution import ToolExecution
from tool_gateway.domain.tool_request import ToolRequest

_EVENT_STATUS_NAMES = {ResultStatus.SUCCESS: "SUCCEEDED"}


def build_approval_required_event(tool_request: ToolRequest, risk_level_name: str, correlation_id: str, now: datetime) -> OutboxRecord:
    """06-event-contracts §"tool.approval.required.v1": "Published when
    approval is required, so domain 06 can create or link an approval
    request."
    """

    return OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(tool_request.tool_request_id),
        event_type="tool.approval.required.v1", event_version="1.0",
        payload={
            "toolRequestId": str(tool_request.tool_request_id),
            "approvalRequestId": (
                str(tool_request.approval_ref.approval_request_id) if tool_request.approval_ref else None
            ),
            "capabilityName": tool_request.capability_name,
            "riskLevel": risk_level_name,
            "ticketId": str(tool_request.ticket_id) if tool_request.ticket_id else None,
            "workflowInstanceId": str(tool_request.workflow_instance_id) if tool_request.workflow_instance_id else None,
            "agentTaskId": str(tool_request.agent_task_id) if tool_request.agent_task_id else None,
            "reason": tool_request.reason,
        },
        occurred_at=now, correlation_id=correlation_id,
    )


def build_denied_completed_event(tool_request: ToolRequest, status_name: str, correlation_id: str, now: datetime) -> OutboxRecord:
    """10-failure-handling §"Policy / Approval Failure": "Gateway publishes
    final tool.completed.v1 with status POLICY_DENIED or APPROVAL_DENIED."
    """

    return OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(tool_request.tool_request_id),
        event_type="tool.completed.v1", event_version="1.0",
        payload={
            "toolRequestId": str(tool_request.tool_request_id),
            "executionId": None,
            "ticketId": str(tool_request.ticket_id) if tool_request.ticket_id else None,
            "ticketCycleId": str(tool_request.ticket_cycle_id) if tool_request.ticket_cycle_id else None,
            "workflowInstanceId": str(tool_request.workflow_instance_id) if tool_request.workflow_instance_id else None,
            "agentTaskId": str(tool_request.agent_task_id) if tool_request.agent_task_id else None,
            "capabilityName": tool_request.capability_name,
            "connectorId": None,
            "status": status_name,
            "summary": tool_request.denial_reason or status_name,
            "structuredOutput": {},
            "resultEnvelopeId": None,
            "evidenceRefs": [],
            "redactionStatus": None,
            "errorCode": None,
            "retryable": False,
        },
        occurred_at=now, correlation_id=correlation_id,
    )


def build_success_completed_event(
    tool_request: ToolRequest, execution: ToolExecution, envelope: ToolResultEnvelope, correlation_id: str, now: datetime,
) -> OutboxRecord:
    """06-event-contracts §"tool.completed.v1" — the full payload shape,
    transcribed field-for-field. Used identically by
    ``execute_tool_request``'s own SUCCESS branch and
    ``reconcile_execution``'s own RECONCILING -> COMPLETED branch.
    """

    return OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(tool_request.tool_request_id),
        event_type="tool.completed.v1", event_version="1.0",
        payload={
            "toolRequestId": str(tool_request.tool_request_id),
            "executionId": str(execution.execution_id),
            "ticketId": str(tool_request.ticket_id) if tool_request.ticket_id else None,
            "ticketCycleId": str(tool_request.ticket_cycle_id) if tool_request.ticket_cycle_id else None,
            "workflowInstanceId": str(tool_request.workflow_instance_id) if tool_request.workflow_instance_id else None,
            "agentTaskId": str(tool_request.agent_task_id) if tool_request.agent_task_id else None,
            "capabilityName": tool_request.capability_name,
            "connectorId": str(execution.connector_id),
            "status": _EVENT_STATUS_NAMES.get(envelope.status, envelope.status.name),
            "summary": envelope.summary,
            "structuredOutput": envelope.structured_output,
            "resultEnvelopeId": str(envelope.result_envelope_id),
            "evidenceRefs": list(envelope.evidence_refs),
            "redactionStatus": envelope.redaction_status.name,
            "errorCode": envelope.error_code,
            "retryable": envelope.retryable,
        },
        occurred_at=now, correlation_id=correlation_id,
    )


def build_terminal_failed_completed_event(
    tool_request: ToolRequest, execution: ToolExecution, correlation_id: str, now: datetime, status_name: str = "TERMINAL_FAILED",
) -> OutboxRecord:
    """SPEC-TG-016 UC-TG-004 step 5: "Gateway publishes final tool.completed.v1"
    once a retryable failure exhausts its attempts (or a reconciled outcome is
    confirmed unrecoverable — SPEC-TG-017 reuses this for that branch too).
    Unlike ``build_denied_completed_event`` (POLICY_DENIED/APPROVAL_DENIED,
    which never had a real execution attempt at all), this carries the real
    ``execution``'s own connectorId/errorCode/retryable — the attempt that
    actually ran and failed, not a placeholder.

    ``status_name`` defaults to ``"TERMINAL_FAILED"`` for every existing
    caller; SPEC-TG-032's own final coverage audit found 10-failure-handling
    §"Reconciliation" names a third, distinct outcome this payload never had
    a way to express — "If the result remains UNCERTAIN for too long, Gateway
    publishes final uncertain result" — 02-business-invariants INV-TG-010's
    "must remain distinguishable... must not be collapsed into a generic
    failure" applies to it exactly the same as the outcomes that section does
    name explicitly. ``reconcile_execution``'s own UNCERTAIN branch passes
    ``"UNCERTAIN"`` here rather than this module growing a near-identical
    sibling function for one field's worth of difference.
    """

    return OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(tool_request.tool_request_id),
        event_type="tool.completed.v1", event_version="1.0",
        payload={
            "toolRequestId": str(tool_request.tool_request_id),
            "executionId": str(execution.execution_id),
            "ticketId": str(tool_request.ticket_id) if tool_request.ticket_id else None,
            "ticketCycleId": str(tool_request.ticket_cycle_id) if tool_request.ticket_cycle_id else None,
            "workflowInstanceId": str(tool_request.workflow_instance_id) if tool_request.workflow_instance_id else None,
            "agentTaskId": str(tool_request.agent_task_id) if tool_request.agent_task_id else None,
            "capabilityName": tool_request.capability_name,
            "connectorId": str(execution.connector_id),
            "status": status_name,
            "summary": tool_request.denial_reason or "tool execution failed and exhausted its retry policy",
            "structuredOutput": {},
            "resultEnvelopeId": None,
            "evidenceRefs": [],
            "redactionStatus": None,
            "errorCode": execution.error_code,
            "retryable": execution.retryable,
        },
        occurred_at=now, correlation_id=correlation_id,
    )


def build_retry_scheduled_event(
    tool_request: ToolRequest, execution: ToolExecution, retry_not_before: datetime, correlation_id: str, now: datetime,
) -> OutboxRecord:
    """06-event-contracts §"tool.execution.retry_scheduled.v1": "Published after
    retryable failure schedules another attempt." No literal JSON example is
    given (unlike ``tool.completed.v1``) — this shape follows the same
    field-naming convention every other event here uses.
    """

    return OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(tool_request.tool_request_id),
        event_type="tool.execution.retry_scheduled.v1", event_version="1.0",
        payload={
            "toolRequestId": str(tool_request.tool_request_id),
            "executionId": str(execution.execution_id),
            "attemptNumber": execution.attempt_number,
            "capabilityName": tool_request.capability_name,
            "connectorId": str(execution.connector_id),
            "errorCode": execution.error_code,
            "retryNotBefore": retry_not_before.isoformat(),
        },
        occurred_at=now, correlation_id=correlation_id,
    )


def build_connector_health_changed_event(connector: ToolConnector, correlation_id: str, now: datetime) -> OutboxRecord:
    """06-event-contracts §"tool.connector.health_changed.v1": "Published after
    connector health changes." Covers both admin-driven transitions
    (``register_connector.update_connector_status`` — ENABLE/DISABLE/DEPRECATE)
    and the automatic ACTIVE<->DEGRADED transitions SPEC-TG-019's own
    ``ConnectorHealthWorker`` drives; neither published this event before this
    spec.
    """

    return OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_CONNECTOR", aggregate_id=str(connector.connector_id),
        event_type="tool.connector.health_changed.v1", event_version="1.0",
        payload={
            "connectorId": str(connector.connector_id), "name": connector.name, "version": connector.version,
            "healthStatus": connector.health_status.name,
        },
        occurred_at=now, correlation_id=correlation_id,
    )


def build_cancelled_event(tool_request: ToolRequest, reason: str | None, correlation_id: str, now: datetime) -> OutboxRecord:
    """06-event-contracts UC-TG-006 step 5: "Gateway finally publishes
    tool.cancelled.v1 or tool.completed.v1 with cancellation metadata." Used by
    ``cancel_tool_request``'s own QUEUED->CANCELLED path and by
    ``cancellation_race.save_resolved_tool_request``'s own losing-outcome
    resolution (``execute_tool_request``/``reconcile_execution``, where the
    original human-supplied cancellation ``reason`` is unknown to the
    resolving worker — already audited by ``cancel_tool_request`` itself, so
    ``None`` there is not a loss of information).
    """

    return OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(tool_request.tool_request_id),
        event_type="tool.cancelled.v1", event_version="1.0",
        payload={"toolRequestId": str(tool_request.tool_request_id), "reason": reason, "status": tool_request.status.name},
        occurred_at=now, correlation_id=correlation_id,
    )
