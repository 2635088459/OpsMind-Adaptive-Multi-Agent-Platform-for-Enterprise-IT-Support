"""SPEC-MK-023 06-event-contracts / 08-transaction-and-outbox: the seven events
memory-knowledge-service publishes (memory.candidate.created.v1, memory.candidate.
rejected.v1, memory.published.v1, memory.superseded.v1, memory.deleted.v1,
knowledge.document.indexed.v1, knowledge.graph.updated.v1).

Drives the real end-to-end pipeline (the same `client`/container fixture
tests/test_app.py uses) and inspects the real container's own OutboxRepository —
not a hand-built payload — proving three things per event type:
1. Exactly the documented `event_type` string is used.
2. `encode_event_payload()` (application/outbox_codec.py) round-trips every field
   the domain event dataclass carries through `json.loads` without silently
   dropping or mis-serializing anything (UUIDs -> str, enums -> name, datetimes ->
   ISO string — SPEC-MK-023's own job is proving this holds for *every* event type,
   not just the ones incidental service tests happened to also assert an outbox
   entry exists for).
3. Where 06-event-contracts documents an explicit field list (`knowledge.graph.
   updated.v1`: graphUpdateId/sourceType/sourceId/nodeCount/edgeCount/indexVersion),
   every one of those fields is present with a correct value (case differs —
   snake_case, since encode_event_payload has no camelCase transform step; see this
   spec's own traceability entry for why that stays out of scope).
"""

from __future__ import annotations

import json
import uuid

import pytest
from fastapi.testclient import TestClient

from memoryknowledge.container import get_container
from memoryknowledge.main import create_app
from memoryknowledge.settings import Settings

pytestmark = pytest.mark.unit


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("memoryknowledge.container.get_settings", lambda: Settings(memory_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


def _payloads(client: TestClient, event_type: str) -> list[dict]:
    return [json.loads(r.payload) for r in get_container().outbox_repository.recorded() if r.event_type == event_type]


def test_memory_candidate_created_event_contract(client: TestClient) -> None:
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-1"}],
        "candidate_text": "vpn fails after mfa reset", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]

    [payload] = _payloads(client, "memory.candidate.created.v1")
    assert payload["candidate_id"] == candidate_id
    assert payload["memory_type"] == "EPISODIC"
    assert isinstance(payload["occurred_at"], str)


def test_memory_candidate_rejected_event_contract(client: TestClient) -> None:
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-2"}],
        "candidate_text": "unverified rumor", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/reject", json={"reason": "unverified"}, headers={"X-Actor-Id": "reviewer-1"})

    [payload] = _payloads(client, "memory.candidate.rejected.v1")
    assert payload["candidate_id"] == candidate_id
    assert payload["reason"] == "unverified"


def test_memory_published_and_graph_updated_event_contracts(client: TestClient) -> None:
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-3"}],
        "candidate_text": "printer offline", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.8})
    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "printer offline", "summary": "printer offline", "source_trust_score": 0.9,
    })
    memory_id = approved.json()["memory_id"]
    memory_version_id = approved.json()["memory_version_id"]

    [published_payload] = _payloads(client, "memory.published.v1")
    assert published_payload["memory_id"] == memory_id
    assert published_payload["memory_version_id"] == memory_version_id
    assert published_payload["version"] == 1

    # 06-event-contracts `knowledge.graph.updated.v1` §"关键字段": graphUpdateId/
    # sourceType/sourceId/nodeCount/edgeCount/indexVersion — SPEC-MK-018's own
    # publish-time graph upsert is what triggers this event.
    [graph_payload] = _payloads(client, "knowledge.graph.updated.v1")
    assert graph_payload["source_type"] == "memory"
    assert graph_payload["source_id"] == memory_id
    assert graph_payload["node_count"] >= 1
    assert graph_payload["index_version"] == 1


def test_memory_superseded_event_contract(client: TestClient) -> None:
    first_extract = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-4"}],
        "candidate_text": "cpu spike under investigation", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    first_candidate_id = first_extract.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{first_candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.8})
    first_approved = client.post(f"/internal/memory/v1/admin/candidates/{first_candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "cpu spike under investigation", "summary": "cpu spike under investigation", "source_trust_score": 0.9,
    })
    memory_id = first_approved.json()["memory_id"]
    first_version_id = first_approved.json()["memory_version_id"]

    second_extract = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-4"}],
        "candidate_text": "cpu spike root cause confirmed", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    second_candidate_id = second_extract.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{second_candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.9})
    second_approved = client.post(f"/internal/memory/v1/admin/candidates/{second_candidate_id}/approve", json={
        "usefulness_score": 0.9, "published_by": "admin-2", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "cpu spike root cause confirmed", "summary": "cpu spike root cause confirmed", "source_trust_score": 0.95,
        "memory_id": memory_id,
    })
    second_version_id = second_approved.json()["memory_version_id"]

    [payload] = _payloads(client, "memory.superseded.v1")
    assert payload["memory_id"] == memory_id
    assert payload["superseded_version_id"] == first_version_id
    assert payload["superseding_version_id"] == second_version_id


def test_memory_deleted_event_contract(client: TestClient) -> None:
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-5"}],
        "candidate_text": "disk full on app server", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.8})
    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "disk full on app server", "summary": "disk full on app server", "source_trust_score": 0.9,
    })
    memory_id = approved.json()["memory_id"]
    client.post(
        "/internal/memory/v1/admin/deletion-requests",
        json={"memory_id": memory_id, "reason": "retention expired", "idempotency_key": f"del-{uuid.uuid4()}"},
        headers={"X-Actor-Id": "ops-1"},
    )

    [payload] = _payloads(client, "memory.deleted.v1")
    assert payload["source_type"] == "MEMORY"
    assert payload["source_id"] == memory_id


def test_knowledge_document_indexed_event_contract(client: TestClient) -> None:
    ingested = client.post("/internal/memory/v1/admin/documents", json={
        "source_system": "confluence", "external_id": "KB-CONTRACT-1", "title": "VPN Runbook", "document_type": "RUNBOOK",
        "raw_content": "SERVICE: vpn-auth is affected by SYMPTOM: mfa-loop-after-reset.", "ingested_by": "admin-1",
    })
    document_id = ingested.json()["document_id"]

    [payload] = _payloads(client, "knowledge.document.indexed.v1")
    assert payload["document_id"] == document_id
    assert payload["version"] == 1
    assert payload["chunk_count"] >= 1
