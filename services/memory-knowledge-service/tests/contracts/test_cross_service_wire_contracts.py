"""SPEC-XOBS-001 Part C: memory-knowledge is the consumer of ticket-workflow's
`ticket.resolved.v1` outbox envelope (relayed by the event-relay sidecar into
POST /internal/memory/v1/events/ticket-resolved). Pin its request schema against the
shared `contracts/` fixture so a rename on either side fails here at change time.

The fixture is the REAL ticket-workflow shape: `eventType` is the *unversioned*
`ticket.resolved` and the payload is nested camelCase — the schema's own
@model_validator must unwrap it.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from memoryknowledge.interfaces.event.schemas import TicketResolvedEventRequest

pytestmark = pytest.mark.unit

CONTRACTS = Path(__file__).resolve().parents[4] / "contracts"


def _fixture(name: str) -> dict:
    path = CONTRACTS / name
    assert path.is_file(), f"missing contract fixture: {path}"
    return json.loads(path.read_text())


def test_ticket_resolved_schema_unwraps_the_raw_ticket_workflow_envelope():
    fixture = _fixture("memory-knowledge-ticket-resolved-source.json")
    model = TicketResolvedEventRequest.model_validate(fixture)

    assert str(model.ticket_id) == fixture["ticketId"]
    assert str(model.ticket_cycle_id) == fixture["payload"]["resolutionCycleId"]
    assert model.resolution_code == fixture["payload"]["resolutionCode"]
    assert model.resolution_summary == fixture["payload"]["resolutionSummary"]
    assert model.resolved_by == fixture["payload"]["resolvedBy"]
    # correlationId is a real UUID on the wire and stays one here.
    assert str(model.correlation_id) == fixture["correlationId"]
