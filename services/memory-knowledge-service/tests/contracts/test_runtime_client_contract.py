"""SPEC-MK-024 05-api-contracts §"Runtime API": the two routes
agent-runtime-service is the intended caller of — `POST /internal/memory/v1/search`
and `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`.

05-api-contracts' own request/response JSON examples use a flatter shape
(`requesterRole` as one field, camelCase keys) than this service's actual, already-
shipping schemas (`requester_type` + `requester_id` + `access_scope.role` — three
fields, snake_case) — a deliberate, pre-existing divergence, not something this spec
introduces or "fixes": `access_scope` mirrors 01-domain-model's own `AccessScope`
value object (`tenant、application、queue、role、classification`) exactly, which is
more complete than the illustrative sample's single flattened field, and snake_case
is this entire service's own established REST convention (every other endpoint this
codebase built already uses it — changing this route alone now would be an
unjustified, real breaking change with no concrete benefit). This spec's own job is
proving the *documented behavioral contract* holds against the real routes:
correlationId/expectedVersion are actually required, and the response shapes 05-api-
contracts calls out (retrievalId/degraded/results[]/provenance/graphPaths,
"必须传 expectedVersion") are actually present end to end — not re-litigating field
casing already decided elsewhere.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from memoryknowledge.container import get_container
from memoryknowledge.domain.ids import TicketCycleId, TicketId, WorkflowInstanceId
from memoryknowledge.domain.working_memory import derive_working_memory_id
from memoryknowledge.main import create_app
from memoryknowledge.settings import Settings

pytestmark = pytest.mark.unit


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("memoryknowledge.container.get_settings", lambda: Settings(memory_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


def _search_request(**overrides) -> dict:
    body = {
        "query": "vpn login fails after mfa reset", "requester_type": "agent", "requester_id": "knowledge-agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    }
    body.update(overrides)
    return body


def test_search_requires_correlation_id(client: TestClient) -> None:
    """05-api-contracts §"通用约束": "Internal API 必须携带 correlation id."."""
    body = _search_request()
    del body["correlation_id"]

    response = client.post("/internal/memory/v1/search", json=body)

    assert response.status_code == 400


def test_search_response_matches_the_documented_shape(client: TestClient) -> None:
    """05-api-contracts §"Runtime API" `POST /internal/memory/v1/search` response:
    retrievalId, degraded, results[] each with resultType/sourceId/sourceVersion/
    snippet/score/provenance{sourceType/sourceRef/redacted}/graphPaths[].
    """
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-1"}],
        "candidate_text": "vpn login fails after mfa reset", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.85})
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "vpn login fails after mfa reset", "summary": "vpn login fails after mfa reset", "source_trust_score": 0.9,
    })

    response = client.post("/internal/memory/v1/search", json=_search_request())

    assert response.status_code == 200
    body = response.json()
    assert set(["retrieval_id", "degraded", "results", "degraded_reason", "graph_degraded"]) <= set(body.keys())
    assert body["results"], "expected the just-published memory to be a search result"
    [result] = body["results"]
    assert set(["result_type", "source_id", "source_version", "snippet", "score", "provenance", "graph_paths"]) <= set(result.keys())
    assert set(["source_type", "source_ref", "redacted"]) == set(result["provenance"].keys())


def test_search_never_returns_raw_content_only_a_redacted_snippet(client: TestClient) -> None:
    """05-api-contracts §"API 原则": "对 Runtime 返回 redacted snippet，不返回 raw document."."""
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-2"}],
        "candidate_text": "contact user@example.com for follow-up", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.85})
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "contact user@example.com for follow-up", "summary": "contact user@example.com for follow-up",
        "source_trust_score": 0.9,
    })

    response = client.post("/internal/memory/v1/search", json=_search_request(query="contact user@example.com"))

    [result] = response.json()["results"]
    assert result["provenance"]["redacted"] is True
    assert "user@example.com" not in result["snippet"]


def test_working_memory_update_requires_expected_version(client: TestClient) -> None:
    """05-api-contracts §"Runtime API" `PATCH /internal/memory/v1/working-memory/
    {workingMemoryId}`: "必须传 expectedVersion."."""
    ticket_id, ticket_cycle_id, workflow_instance_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    derived_id = str(derive_working_memory_id(
        TicketId(uuid.UUID(ticket_id)), TicketCycleId(uuid.UUID(ticket_cycle_id)), WorkflowInstanceId(uuid.UUID(workflow_instance_id)),
    ))
    body = {
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()), "add_facts": ["vpn down"],
    }

    response = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json=body)

    assert response.status_code == 400


def test_working_memory_update_response_matches_the_documented_shape(client: TestClient) -> None:
    ticket_id, ticket_cycle_id, workflow_instance_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    derived_id = str(derive_working_memory_id(
        TicketId(uuid.UUID(ticket_id)), TicketCycleId(uuid.UUID(ticket_cycle_id)), WorkflowInstanceId(uuid.UUID(workflow_instance_id)),
    ))

    response = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": 0, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()), "add_facts": ["vpn down"],
    })

    assert response.status_code == 200
    body = response.json()
    assert set([
        "working_memory_id", "version", "status", "facts", "hypotheses", "rejected_hypotheses", "completed_tasks",
        "pending_tasks", "tool_evidence_refs", "approval_decision_refs", "context_summary", "updated_at",
    ]) <= set(body.keys())
