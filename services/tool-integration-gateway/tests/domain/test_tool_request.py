"""03-state-machine §"Tool Request State Machine" + 02-business-invariants
INV-TG-001/INV-TG-005/INV-TG-010.
"""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from tool_gateway.domain.enums import RequestedByType, RiskLevel, ToolRequestStatus
from tool_gateway.domain.errors import InvalidToolRequestTransitionException, ToolRequestMissingReasonException
from tool_gateway.domain.ids import IdempotencyKey, ToolRequestId
from tool_gateway.domain.tool_request import ToolRequest
from tool_gateway.domain.values import RiskDecisionRef

_NOW = datetime(2026, 1, 1, tzinfo=UTC)


def _submit() -> ToolRequest:
    return ToolRequest.submit(
        tool_request_id=ToolRequestId.new_id(), idempotency_key=IdempotencyKey("idem-1"), payload_hash="hash-1",
        requested_by_type=RequestedByType.AGENT, requested_by_id="agent-1", capability_name="kubernetes.getPodLogs",
        input_payload={}, reason="investigate crash loop", submitted_at=_NOW,
    )


def test_submit_starts_at_received() -> None:
    tool_request = _submit()
    assert tool_request.status is ToolRequestStatus.RECEIVED


def test_submit_requires_non_blank_reason() -> None:
    with pytest.raises(ToolRequestMissingReasonException):
        ToolRequest.submit(
            tool_request_id=ToolRequestId.new_id(), idempotency_key=IdempotencyKey("idem-1"), payload_hash="hash-1",
            requested_by_type=RequestedByType.AGENT, requested_by_id="agent-1", capability_name="kubernetes.getPodLogs",
            input_payload={}, reason="   ", submitted_at=_NOW,
        )


def test_low_risk_path_reaches_queued_without_waiting_for_approval() -> None:
    """04-use-cases UC-TG-002."""

    risk = RiskDecisionRef(decision_id="d-1", risk_level=RiskLevel.LOW, requires_approval=False, decided_at=_NOW, decided_by="policy")
    tool_request = _submit().begin_validation(_NOW).begin_policy_check(_NOW).auto_approve(risk, _NOW).enqueue(_NOW)
    assert tool_request.status is ToolRequestStatus.QUEUED


def test_high_risk_path_waits_for_approval() -> None:
    """04-use-cases UC-TG-003; INV-TG-005."""

    risk = RiskDecisionRef(decision_id="d-1", risk_level=RiskLevel.HIGH, requires_approval=True, decided_at=_NOW, decided_by="policy")
    tool_request = _submit().begin_validation(_NOW).begin_policy_check(_NOW)
    tool_request = tool_request.require_approval(risk, approval_ref=None, now=_NOW)
    assert tool_request.status is ToolRequestStatus.WAITING_APPROVAL

    granted = tool_request.receive_approval_granted(_NOW).enqueue(_NOW)
    assert granted.status is ToolRequestStatus.QUEUED

    denied = tool_request.receive_approval_denied("too risky", _NOW)
    assert denied.status is ToolRequestStatus.APPROVAL_DENIED
    assert denied.denial_reason == "too risky"


def test_cannot_skip_straight_from_received_to_queued() -> None:
    """INV-TG-001: nothing may bypass validation/policy/approval."""

    tool_request = _submit()
    with pytest.raises(InvalidToolRequestTransitionException):
        tool_request.enqueue(_NOW)


def test_cannot_transition_out_of_a_terminal_status() -> None:
    risk = RiskDecisionRef(decision_id="d-1", risk_level=RiskLevel.LOW, requires_approval=False, decided_at=_NOW, decided_by="policy")
    tool_request = _submit().begin_validation(_NOW).begin_policy_check(_NOW).auto_approve(risk, _NOW)
    tool_request = tool_request.enqueue(_NOW).begin_execution(_NOW)
    completed = tool_request.complete(result_envelope_id=None, now=_NOW)
    assert completed.status.is_terminal()
    with pytest.raises(InvalidToolRequestTransitionException):
        completed.begin_execution(_NOW)


def test_cancel_from_queue() -> None:
    risk = RiskDecisionRef(decision_id="d-1", risk_level=RiskLevel.LOW, requires_approval=False, decided_at=_NOW, decided_by="policy")
    tool_request = _submit().begin_validation(_NOW).begin_policy_check(_NOW).auto_approve(risk, _NOW).enqueue(_NOW)
    cancelled = tool_request.cancel_from_queue(_NOW)
    assert cancelled.status is ToolRequestStatus.CANCELLED


def test_cancel_during_execution_can_still_complete() -> None:
    """03-state-machine: CANCEL_REQUESTED -> COMPLETED — see state_machine.py's
    own module docstring for why this edge exists.
    """

    risk = RiskDecisionRef(decision_id="d-1", risk_level=RiskLevel.LOW, requires_approval=False, decided_at=_NOW, decided_by="policy")
    tool_request = _submit().begin_validation(_NOW).begin_policy_check(_NOW).auto_approve(risk, _NOW)
    tool_request = tool_request.enqueue(_NOW).begin_execution(_NOW).request_cancel_during_execution(_NOW)
    completed = tool_request.complete_after_cancel_requested(result_envelope_id=None, now=_NOW)
    assert completed.status is ToolRequestStatus.COMPLETED


def test_failed_retry_and_terminal_failure() -> None:
    risk = RiskDecisionRef(decision_id="d-1", risk_level=RiskLevel.LOW, requires_approval=False, decided_at=_NOW, decided_by="policy")
    tool_request = _submit().begin_validation(_NOW).begin_policy_check(_NOW).auto_approve(risk, _NOW)
    tool_request = tool_request.enqueue(_NOW).begin_execution(_NOW).fail(_NOW)
    assert tool_request.status is ToolRequestStatus.FAILED

    retried = tool_request.retry(_NOW)
    assert retried.status is ToolRequestStatus.QUEUED

    terminal = tool_request.terminal_fail("max attempts reached", _NOW)
    assert terminal.status is ToolRequestStatus.TERMINAL_FAILED
