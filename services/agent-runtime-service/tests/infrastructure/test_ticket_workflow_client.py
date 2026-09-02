"""SPEC-ARO-038 (phase-10 Conversational Intake): HttpTicketWorkflowClient, exercised
against httpx.MockTransport, never a live 02-ticket-workflow instance.
"""

from __future__ import annotations

import json
import uuid

import httpx
import pytest

from agentruntime.application.exceptions import (
    TicketCreationFailedException,
    TicketTriageFailedException,
)
from agentruntime.domain.ids import TicketId
from agentruntime.infrastructure.ticket_workflow_client import HttpTicketWorkflowClient

pytestmark = pytest.mark.unit


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_create_ticket_forwards_the_employee_bearer_token_and_idempotency_key() -> None:
    captured = {}
    ticket_id = uuid.uuid4()
    resolution_cycle_id = uuid.uuid4()

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["headers"] = request.headers
        captured["body"] = json.loads(request.content)
        return httpx.Response(201, json={
            "ticketId": str(ticket_id), "displayId": "INC-1000", "status": "NEW",
            "createdAt": "2026-09-01T00:00:00Z", "version": 0, "resolutionCycleId": str(resolution_cycle_id),
        })

    client = HttpTicketWorkflowClient("http://ticket-workflow:8080", _client(handler))

    ref = client.create_ticket("employee-jwt-abc", "conv-1")

    assert ref.ticket_id.value == ticket_id
    assert ref.ticket_cycle_id.value == resolution_cycle_id
    assert ref.version == 0
    assert ref.display_id == "INC-1000"
    assert captured["url"] == "http://ticket-workflow:8080/api/v1/tickets"
    assert captured["headers"]["authorization"] == "Bearer employee-jwt-abc"
    assert captured["headers"]["idempotency-key"] == "conv-1"
    # Never the employee's own service-identity token — see TicketWorkflowClientPort's
    # own docstring for why this must be the employee's own forwarded bearer token.
    body = captured["body"]
    assert body["applicationCode"] == "OTHER"
    assert body["source"] == "PORTAL"
    assert body["title"]
    assert body["description"]


def test_create_ticket_raises_on_a_non_201_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(403, json={"error": "forbidden"})

    client = HttpTicketWorkflowClient("http://ticket-workflow:8080", _client(handler))

    with pytest.raises(TicketCreationFailedException):
        client.create_ticket("employee-jwt-abc", "conv-1")


def test_create_ticket_raises_on_a_malformed_response_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(201, json={"unexpected": "shape"})

    client = HttpTicketWorkflowClient("http://ticket-workflow:8080", _client(handler))

    with pytest.raises(TicketCreationFailedException):
        client.create_ticket("employee-jwt-abc", "conv-1")


def test_create_ticket_raises_on_a_network_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    client = HttpTicketWorkflowClient("http://ticket-workflow:8080", _client(handler))

    with pytest.raises(TicketCreationFailedException):
        client.create_ticket("employee-jwt-abc", "conv-1")


class _FakeTokenProvider:
    def get_token(self) -> str:
        return "service-identity-token"


def test_triage_ticket_authenticates_via_the_service_identity_not_the_employee_token() -> None:
    captured = {}
    ticket_id = TicketId(uuid.uuid4())

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["headers"] = request.headers
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"ticketId": str(ticket_id), "version": 1})

    client = HttpTicketWorkflowClient("http://ticket-workflow:8080", _client(handler), token_provider=_FakeTokenProvider())

    ref = client.triage_ticket(ticket_id, 0, "cat-1", "queue-1", "HIGH", "needs a human", "triage-1")

    assert ref.version == 1
    assert captured["url"] == f"http://ticket-workflow:8080/api/v1/tickets/{ticket_id}/triage"
    assert captured["headers"]["authorization"] == "Bearer service-identity-token"
    assert captured["headers"]["idempotency-key"] == "triage-1"
    assert captured["headers"]["if-match"] == '"0"'
    assert captured["body"] == {"categoryId": "cat-1", "priority": "HIGH", "supportQueueId": "queue-1", "reason": "needs a human"}


def test_triage_ticket_raises_when_no_token_provider_is_configured() -> None:
    client = HttpTicketWorkflowClient("http://ticket-workflow:8080", _client(lambda r: httpx.Response(200, json={})))

    with pytest.raises(TicketTriageFailedException):
        client.triage_ticket(TicketId(uuid.uuid4()), 0, "cat-1", "queue-1", "HIGH", "reason", "triage-2")


def test_triage_ticket_raises_on_a_non_200_response() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(409, json={"error": "version conflict"})

    client = HttpTicketWorkflowClient("http://ticket-workflow:8080", _client(handler), token_provider=_FakeTokenProvider())

    with pytest.raises(TicketTriageFailedException):
        client.triage_ticket(TicketId(uuid.uuid4()), 0, "cat-1", "queue-1", "HIGH", "reason", "triage-3")
