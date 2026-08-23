"""SPEC-TG-018 "Tool Request Cancellation" 09-concurrency-and-idempotency
§"Concurrent Cancellation": "Completion commits first: cancel returns final
completed. Cancel commits first and connector has not been called: request
enters CANCELLED. Cancel commits first but connector was called: request
enters CANCEL_REQUESTED and waits for connector hook/reconciliation."

``cancel_tool_request``'s own EXECUTING -> CANCEL_REQUESTED transition and
whichever service is resolving that same attempt's outcome
(``execute_tool_request``'s own SUCCESS/FAILED branches,
``reconcile_execution``'s own SUCCESS/failure branches — all four reach a
final ``expected_status=EXECUTING`` CAS save) race at the database layer.
Before this spec, ``cancel_tool_request`` papered over the race by confirming
CANCELLED immediately after requesting it (see that module's own prior
docstring, which named this exact deferral) — a real concurrent deployment
could still have a worker's own CAS save silently lose against a cancel that
committed first, with nothing to catch it. This module is the one place every
resolving branch handles that loss identically instead of four independent
copies.
"""

from __future__ import annotations

from datetime import datetime

from tool_gateway.application.exceptions import ToolRequestStatusConflictException
from tool_gateway.domain.enums import ToolRequestStatus
from tool_gateway.domain.ids import ResultEnvelopeId
from tool_gateway.domain.tool_request import ToolRequest
from tool_gateway.ports.storage_port import ToolRequestRepository


def save_resolved_tool_request(
    tool_request_repository: ToolRequestRepository, resolved: ToolRequest, now: datetime,
    result_envelope_id: ResultEnvelopeId | None = None,
) -> ToolRequest:
    """Attempts the normal EXECUTING-scoped CAS save first. If a concurrent
    cancel already won the race (the row moved to CANCEL_REQUESTED first),
    re-resolves onto that fact instead of propagating the conflict: a
    COMPLETED outcome still lands COMPLETED (a side effect that already
    happened cannot be un-done by a cancel that lost the race), any other
    outcome (FAILED/TERMINAL_FAILED/QUEUED-for-retry) honors the cancel and
    lands CANCELLED — the retry-vs-terminal distinction becomes moot once a
    cancel has been requested.
    """

    try:
        return tool_request_repository.save(resolved, expected_status=ToolRequestStatus.EXECUTING)
    except ToolRequestStatusConflictException:
        current = tool_request_repository.find_by_id(resolved.tool_request_id)
        if current is None or current.status is not ToolRequestStatus.CANCEL_REQUESTED:
            raise
        if resolved.status is ToolRequestStatus.COMPLETED and result_envelope_id is not None:
            winner = current.complete_after_cancel_requested(result_envelope_id, now)
        else:
            winner = current.confirm_cancelled(now)
        return tool_request_repository.save(winner, expected_status=ToolRequestStatus.CANCEL_REQUESTED)
