"""01-domain-model §"ToolRequest": "the core aggregate in Tool Integration
Gateway. It represents a tool invocation intent submitted by Agent Runtime and
governed by the Gateway." 03-state-machine §"Tool Request State Machine" is
enforced entirely through tool_gateway.domain.state_machine.TOOL_REQUEST_TRANSITIONS
— every method below is a thin, named wrapper around one edge of that table.
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass
from datetime import datetime

from tool_gateway.domain.enums import RequestedByType, ToolRequestStatus
from tool_gateway.domain.errors import InvalidToolRequestTransitionException, ToolRequestMissingReasonException
from tool_gateway.domain.ids import (
    AgentTaskId,
    ConnectorId,
    IdempotencyKey,
    ResultEnvelopeId,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowInstanceId,
)
from tool_gateway.domain.state_machine import TOOL_REQUEST_TRANSITIONS, is_allowed
from tool_gateway.domain.values import ApprovalRequestRef, RiskDecisionRef


def _transition(current: ToolRequestStatus, target: ToolRequestStatus) -> ToolRequestStatus:
    if not is_allowed(current, target, TOOL_REQUEST_TRANSITIONS):
        raise InvalidToolRequestTransitionException(current, target)
    return target


@dataclass(frozen=True, slots=True)
class ToolRequest:
    """01-domain-model §"ToolRequest" field list, transcribed 1:1, plus two
    SPEC-TG-002/SPEC-TG-006 extensions:

    - ``payload_hash`` — a 07-data-model column (``tool_requests.payload_hash``)
      not itself in 01-domain-model's own list. 09-concurrency-and-idempotency
      §"Tool Request Idempotency": "Same payload hash: return existing
      ToolRequest. Different payload hash: return IDEMPOTENCY_CONFLICT."
    - ``resolved_connector_id``/``resolved_connector_version`` — 02-business-
      invariants INV-TG-008: "Every connector input/output schema must be
      versioned. Tool Request records the schema version used at submission
      time so historical requests remain interpretable after connector
      upgrades." Bound once, at VALIDATING (``bind_connector()``), and reused
      verbatim by ``execute_tool_request`` — re-resolving by capability name at
      execution time would let a connector upgrade between accept and execute
      silently swap in a different schema/version than what was actually
      validated at intake.
    """

    tool_request_id: ToolRequestId
    idempotency_key: IdempotencyKey
    payload_hash: str
    ticket_id: TicketId | None
    ticket_cycle_id: TicketCycleId | None
    workflow_instance_id: WorkflowInstanceId | None
    agent_task_id: AgentTaskId | None
    requested_by_type: RequestedByType
    requested_by_id: str
    capability_name: str
    tool_name: str | None
    input_payload: dict
    reason: str
    status: ToolRequestStatus
    created_at: datetime
    updated_at: datetime
    risk_snapshot: RiskDecisionRef | None = None
    approval_ref: ApprovalRequestRef | None = None
    result_envelope_id: ResultEnvelopeId | None = None
    denial_reason: str | None = None
    resolved_connector_id: ConnectorId | None = None
    resolved_connector_version: str | None = None
    retry_not_before: datetime | None = None
    """SPEC-TG-016 09-concurrency-and-idempotency/10-failure-handling: 07-data-
    model's own ``tool_requests`` column list predates retry *scheduling* (it
    only names ``status``) — a request re-queued after a retryable failure
    still needs to sit out its connector's own ``RetryPolicy.backoff_seconds``
    before a worker claims it again, the same way ``CredentialBindingRow.
    created_at`` (SPEC-TG-012) needed a column 07-data-model never listed. Only
    meaningful while ``status is QUEUED`` from a retry (``retry()`` sets it;
    every other QUEUED-reaching transition leaves it ``None``, meaning
    immediately claimable).
    """

    @staticmethod
    def submit(
        tool_request_id: ToolRequestId,
        idempotency_key: IdempotencyKey,
        payload_hash: str,
        requested_by_type: RequestedByType,
        requested_by_id: str,
        capability_name: str,
        input_payload: dict,
        reason: str,
        submitted_at: datetime,
        ticket_id: TicketId | None = None,
        ticket_cycle_id: TicketCycleId | None = None,
        workflow_instance_id: WorkflowInstanceId | None = None,
        agent_task_id: AgentTaskId | None = None,
        tool_name: str | None = None,
    ) -> "ToolRequest":
        """04-use-cases UC-TG-001 step 3: "Gateway persists ToolRequest." Always
        starts life at RECEIVED (03-state-machine: "Gateway received the request
        but has not completed validation persistence.").
        """

        if not reason or not reason.strip():
            raise ToolRequestMissingReasonException()
        return ToolRequest(
            tool_request_id=tool_request_id, idempotency_key=idempotency_key, payload_hash=payload_hash,
            ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id, workflow_instance_id=workflow_instance_id,
            agent_task_id=agent_task_id, requested_by_type=requested_by_type, requested_by_id=requested_by_id,
            capability_name=capability_name, tool_name=tool_name, input_payload=input_payload, reason=reason,
            status=ToolRequestStatus.RECEIVED, created_at=submitted_at, updated_at=submitted_at,
        )

    def begin_validation(self, now: datetime) -> "ToolRequest":
        """RECEIVED -> VALIDATING."""

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.VALIDATING), updated_at=now)

    def bind_connector(self, connector_id: ConnectorId, connector_version: str, now: datetime) -> "ToolRequest":
        """INV-TG-008: records the schema version resolved at submission time.
        No status transition of its own — called from within VALIDATING, right
        after capability resolution succeeds.
        """

        return dataclasses.replace(
            self, resolved_connector_id=connector_id, resolved_connector_version=connector_version, updated_at=now,
        )

    def reject(self, reason: str, now: datetime) -> "ToolRequest":
        """{RECEIVED,VALIDATING} -> REJECTED. 03-state-machine: "invalid request;
        never enters execution."
        """

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.REJECTED), denial_reason=reason, updated_at=now,
        )

    def begin_policy_check(self, now: datetime) -> "ToolRequest":
        """VALIDATING -> POLICY_CHECKING."""

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.POLICY_CHECKING), updated_at=now)

    def require_approval(self, risk_snapshot: RiskDecisionRef, approval_ref: ApprovalRequestRef, now: datetime) -> "ToolRequest":
        """POLICY_CHECKING -> WAITING_APPROVAL. 04-use-cases UC-TG-003 step 3."""

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.WAITING_APPROVAL),
            risk_snapshot=risk_snapshot, approval_ref=approval_ref, updated_at=now,
        )

    def auto_approve(self, risk_snapshot: RiskDecisionRef, now: datetime) -> "ToolRequest":
        """POLICY_CHECKING -> APPROVED. 04-use-cases UC-TG-002 step 2 (low-risk,
        no-approval-required path) — see state_machine.py's own module docstring
        for why this edge exists beyond the literal 03-state-machine diagram.
        """

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.APPROVED), risk_snapshot=risk_snapshot, updated_at=now,
        )

    def deny_policy(self, reason: str, now: datetime) -> "ToolRequest":
        """POLICY_CHECKING -> POLICY_DENIED."""

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.POLICY_DENIED), denial_reason=reason, updated_at=now,
        )

    def receive_approval_granted(self, now: datetime) -> "ToolRequest":
        """WAITING_APPROVAL -> APPROVED. 04-use-cases UC-TG-003 step 4: "After
        consuming approval.granted.v1, Gateway moves to QUEUED" — modelled as two
        steps (APPROVED, then enqueue()) to keep the diagram's own explicit
        APPROVED -> QUEUED edge as the single place that transition happens.
        """

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.APPROVED), updated_at=now)

    def receive_approval_denied(self, reason: str, now: datetime) -> "ToolRequest":
        """WAITING_APPROVAL -> APPROVAL_DENIED. 04-use-cases UC-TG-003 step 5."""

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.APPROVAL_DENIED), denial_reason=reason, updated_at=now,
        )

    def enqueue(self, now: datetime) -> "ToolRequest":
        """APPROVED -> QUEUED."""

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.QUEUED), updated_at=now)

    def begin_execution(self, now: datetime) -> "ToolRequest":
        """QUEUED -> EXECUTING."""

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.EXECUTING), updated_at=now)

    def complete(self, result_envelope_id: ResultEnvelopeId, now: datetime) -> "ToolRequest":
        """EXECUTING -> COMPLETED. 03-state-machine: "an execution attempt ended
        with final result and result event was published." Does NOT advance
        Ticket/Workflow state (INV-TG-002).
        """

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.COMPLETED),
            result_envelope_id=result_envelope_id, updated_at=now,
        )

    def complete_after_cancel_requested(self, result_envelope_id: ResultEnvelopeId, now: datetime) -> "ToolRequest":
        """CANCEL_REQUESTED -> COMPLETED — see state_machine.py's own module
        docstring for why this edge exists.
        """

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.COMPLETED),
            result_envelope_id=result_envelope_id, updated_at=now,
        )

    def fail(self, now: datetime) -> "ToolRequest":
        """EXECUTING -> FAILED (retryable)."""

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.FAILED), updated_at=now)

    def retry(self, now: datetime, retry_not_before: datetime | None = None) -> "ToolRequest":
        """FAILED -> QUEUED. 04-use-cases UC-TG-004 step 3: "Gateway creates the
        next attempt based on retry policy" — ``retry_not_before`` (SPEC-TG-016's
        own backoff scheduling; see this dataclass's own field docstring)
        defaults to ``None`` (immediately claimable) for any caller that has no
        backoff policy to apply.
        """

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.QUEUED), retry_not_before=retry_not_before, updated_at=now,
        )

    def terminal_fail(self, reason: str, now: datetime) -> "ToolRequest":
        """FAILED -> TERMINAL_FAILED. 04-use-cases UC-TG-004 step 4: "If max
        attempts are reached, ToolRequest enters TERMINAL_FAILED."
        """

        return dataclasses.replace(
            self, status=_transition(self.status, ToolRequestStatus.TERMINAL_FAILED), denial_reason=reason, updated_at=now,
        )

    def cancel_from_queue(self, now: datetime) -> "ToolRequest":
        """QUEUED -> CANCELLED. 04-use-cases UC-TG-006 step 3: "If execution has
        not started, ToolRequest enters CANCELLED."
        """

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.CANCELLED), updated_at=now)

    def request_cancel_during_execution(self, now: datetime) -> "ToolRequest":
        """EXECUTING -> CANCEL_REQUESTED. 04-use-cases UC-TG-006 step 4."""

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.CANCEL_REQUESTED), updated_at=now)

    def confirm_cancelled(self, now: datetime) -> "ToolRequest":
        """CANCEL_REQUESTED -> CANCELLED."""

        return dataclasses.replace(self, status=_transition(self.status, ToolRequestStatus.CANCELLED), updated_at=now)
