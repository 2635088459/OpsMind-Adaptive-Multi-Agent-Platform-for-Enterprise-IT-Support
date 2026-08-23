"""SPEC-TG-018: unit coverage for ``cancellation_race.save_resolved_tool_request``
directly against ``InMemoryToolRequestRepository`` — isolates the CAS-conflict
resolution logic from the full ``execute_tool_request``/``reconcile_execution``
flow. See that module's own docstring for the 09-concurrency-and-idempotency
"Concurrent Cancellation" text this implements.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from tool_gateway.adapters.db.repositories import InMemoryToolRequestRepository
from tool_gateway.application.cancellation_race import save_resolved_tool_request
from tool_gateway.application.exceptions import ToolRequestStatusConflictException
from tool_gateway.domain.enums import RequestedByType, RiskLevel, ToolRequestStatus
from tool_gateway.domain.ids import IdempotencyKey, ResultEnvelopeId, ToolRequestId
from tool_gateway.domain.tool_request import ToolRequest
from tool_gateway.domain.values import RiskDecisionRef

_NOW = datetime(2026, 1, 1, tzinfo=UTC)


def _executing_request() -> ToolRequest:
    submitted = ToolRequest.submit(
        tool_request_id=ToolRequestId.new_id(), idempotency_key=IdempotencyKey(f"idem-{uuid.uuid4()}"), payload_hash="hash-1",
        requested_by_type=RequestedByType.AGENT, requested_by_id="agent-1", capability_name="kubernetes.getPodLogs",
        input_payload={}, reason="investigate", submitted_at=_NOW,
    )
    risk = RiskDecisionRef(decision_id="d-1", risk_level=RiskLevel.LOW, requires_approval=False, decided_at=_NOW, decided_by="policy")
    queued = submitted.begin_validation(_NOW).begin_policy_check(_NOW).auto_approve(risk, _NOW).enqueue(_NOW)
    return queued.begin_execution(_NOW)


def test_save_resolved_tool_request_no_conflict_saves_normally() -> None:
    repository = InMemoryToolRequestRepository()
    executing = _executing_request()
    repository.save(executing, expected_status=None)

    completed = executing.complete(ResultEnvelopeId.new_id(), _NOW)
    saved = save_resolved_tool_request(repository, completed, _NOW, completed.result_envelope_id)
    assert saved.status is ToolRequestStatus.COMPLETED


def test_save_resolved_tool_request_success_outcome_wins_over_lost_cancel_race() -> None:
    """09-concurrency-and-idempotency: "Completion commits first: cancel
    returns final completed" — here the mirror case, a SUCCESS outcome
    resolving AFTER a cancel already committed CANCEL_REQUESTED still lands
    COMPLETED (the side effect genuinely happened; a cancel that lost the
    race cannot un-do it).
    """

    repository = InMemoryToolRequestRepository()
    executing = _executing_request()
    repository.save(executing, expected_status=None)

    # Simulates cancel_tool_request committing CANCEL_REQUESTED first, while
    # a worker (holding its own in-memory ``executing`` copy) is still mid-way
    # through resolving the same attempt's outcome.
    cancel_requested = executing.request_cancel_during_execution(_NOW)
    repository.save(cancel_requested, expected_status=ToolRequestStatus.EXECUTING)

    resolved_success = executing.complete(ResultEnvelopeId.new_id(), _NOW)
    saved = save_resolved_tool_request(repository, resolved_success, _NOW, resolved_success.result_envelope_id)
    assert saved.status is ToolRequestStatus.COMPLETED


def test_save_resolved_tool_request_failure_outcome_honors_the_cancel() -> None:
    """09-concurrency-and-idempotency: "Cancel commits first but connector was
    called: request enters CANCEL_REQUESTED and waits for connector hook/
    reconciliation" — a non-SUCCESS resolution (here TERMINAL_FAILED) after a
    cancel already committed lands CANCELLED, not TERMINAL_FAILED.
    """

    repository = InMemoryToolRequestRepository()
    executing = _executing_request()
    repository.save(executing, expected_status=None)

    cancel_requested = executing.request_cancel_during_execution(_NOW)
    repository.save(cancel_requested, expected_status=ToolRequestStatus.EXECUTING)

    resolved_failed = executing.fail(_NOW).terminal_fail("connector error", _NOW)
    saved = save_resolved_tool_request(repository, resolved_failed, _NOW)
    assert saved.status is ToolRequestStatus.CANCELLED


def test_save_resolved_tool_request_propagates_conflict_when_not_a_cancel_race() -> None:
    """A CAS conflict for any reason OTHER than a pending cancel (e.g. a
    genuinely duplicate/racing resolver) must still surface, not be silently
    swallowed.
    """

    repository = InMemoryToolRequestRepository()
    executing = _executing_request()
    repository.save(executing, expected_status=None)
    # The row already moved on to COMPLETED via some other path — not a cancel.
    repository.save(executing.complete(ResultEnvelopeId.new_id(), _NOW), expected_status=ToolRequestStatus.EXECUTING)

    resolved = executing.complete(ResultEnvelopeId.new_id(), _NOW)
    with pytest.raises(ToolRequestStatusConflictException):
        save_resolved_tool_request(repository, resolved, _NOW, resolved.result_envelope_id)
