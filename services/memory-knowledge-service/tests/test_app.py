"""SPEC-MK-001 acceptance-criteria: "实现 Memory 模块与包边界 的最小闭环能力" — drives the
full FastAPI app through a realistic end-to-end lifecycle: ingest a document -> extract a
memory candidate -> validate -> approve/publish -> search finds it with provenance ->
deprecate -> delete -> retrieval visibility is gone, plus the Working Memory PATCH surface
and the admin outbox/graph endpoints. Stays on SPEC-MK-001's in-memory adapters (fast,
hermetic, no Docker). Mirrors agent-runtime-service's own tests/test_app.py.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from memoryknowledge.container import get_container
from memoryknowledge.domain.ids import TicketCycleId, TicketId, WorkflowInstanceId
from memoryknowledge.domain.working_memory import derive_working_memory_id
from memoryknowledge.main import create_app

pytestmark = pytest.mark.unit


@pytest.fixture
def client():
    get_container.cache_clear()
    return TestClient(create_app())


def test_health(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def _extract_and_publish_candidate(client: TestClient, *, text: str = "vpn login fails after mfa reset") -> dict:
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-1"}],
        "candidate_text": text, "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    assert extracted.status_code == 201
    candidate_id = extracted.json()["candidate_id"]

    validated = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={
        "source_refs_trusted": True, "confidence_score": 0.85,
    })
    assert validated.status_code == 200
    assert validated.json()["status"] == "VALIDATED"

    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": text, "summary": text, "source_trust_score": 0.9,
    })
    assert approved.status_code == 200
    assert approved.json()["status"] == "ACTIVE"
    return approved.json()


def test_candidate_pipeline_to_publish_and_search_closes_the_loop(client: TestClient) -> None:
    published = _extract_and_publish_candidate(client)
    memory_id = published["memory_id"]

    search = client.post("/internal/memory/v1/search", json={
        "query": "vpn login fails after mfa reset", "requester_type": "agent", "requester_id": "knowledge-agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    assert search.status_code == 200
    body = search.json()
    assert body["degraded"] is False
    assert any(r["source_id"] == memory_id for r in body["results"])
    assert all(r["provenance"]["redacted"] for r in body["results"])


def test_reject_candidate_publishes_rejected_event_and_never_reaches_search(client: TestClient) -> None:
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-2"}],
        "candidate_text": "unverified rumor about outage", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]

    rejected = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/reject", json={"reason": "unverified"})
    assert rejected.status_code == 200
    assert rejected.json()["status"] == "REJECTED"

    # A rejected candidate can never be approved afterward.
    approve_after_reject = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.5, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "x", "summary": "x", "source_trust_score": 0.5,
    })
    assert approve_after_reject.status_code == 409
    assert approve_after_reject.json()["error"]["code"] == "INVALID_STATE_TRANSITION"


def test_deprecate_then_delete_a_published_memory_removes_it_from_search(client: TestClient) -> None:
    published = _extract_and_publish_candidate(client, text="printer jam on floor 3")
    memory_id = published["memory_id"]

    deprecated = client.post(
        f"/internal/memory/v1/admin/memories/{memory_id}/deprecate",
        json={"idempotency_key": f"dep-{uuid.uuid4()}"}, headers={"X-Actor-Id": "ops-1"},
    )
    assert deprecated.status_code == 200
    assert deprecated.json()["status"] == "DEPRECATED"

    search_after_deprecate = client.post("/internal/memory/v1/search", json={
        "query": "printer jam on floor 3", "requester_type": "agent", "requester_id": "agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    assert not any(r["source_id"] == memory_id for r in search_after_deprecate.json()["results"])

    deletion = client.post(
        "/internal/memory/v1/admin/deletion-requests",
        json={"memory_id": memory_id, "reason": "retention expired", "idempotency_key": f"del-{uuid.uuid4()}"},
        headers={"X-Actor-Id": "ops-1"},
    )
    assert deletion.status_code == 200
    assert deletion.json()["versions_deleted"] == 1


def test_deletion_requires_actor_header(client: TestClient) -> None:
    response = client.post(
        "/internal/memory/v1/admin/deletion-requests",
        json={"memory_id": str(uuid.uuid4()), "reason": "x", "idempotency_key": "del-no-actor"},
    )
    # 05-api-contracts: "Admin API 必须携带 actor id" — a missing X-Actor-Id header is a
    # request validation failure, mapped through the same VALIDATION_ERROR envelope every
    # other malformed request gets (interfaces.errors.register_exception_handlers).
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


def test_working_memory_create_then_update_enforces_optimistic_version(client: TestClient) -> None:
    ticket_id, ticket_cycle_id, workflow_instance_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    derived_id = str(derive_working_memory_id(TicketId(uuid.UUID(ticket_id)), TicketCycleId(uuid.UUID(ticket_cycle_id)), WorkflowInstanceId(uuid.UUID(workflow_instance_id))))

    created = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": 0, "updated_by": "agent-1", "add_facts": ["vpn down"],
    })
    assert created.status_code == 200
    assert created.json()["facts"] == ["vpn down"]
    current_version = created.json()["version"]

    stale_update = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": 0, "updated_by": "agent-1", "add_facts": ["stale write"],
    })
    assert stale_update.status_code == 409
    assert stale_update.json()["error"]["code"] == "WORKING_MEMORY_VERSION_CONFLICT"

    updated = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": current_version, "updated_by": "agent-1", "add_facts": ["mfa reset needed"],
    })
    assert updated.status_code == 200
    assert set(updated.json()["facts"]) == {"vpn down", "mfa reset needed"}


def test_working_memory_path_id_must_match_derived_scope_id(client: TestClient) -> None:
    wrong_id = str(uuid.uuid4())
    response = client.patch(f"/internal/memory/v1/working-memory/{wrong_id}", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_instance_id": str(uuid.uuid4()),
        "expected_version": 0, "updated_by": "agent-1",
    })
    assert response.status_code == 400


def test_ingest_document_with_graph_extraction_and_admin_node_lookup(client: TestClient) -> None:
    ingested = client.post("/internal/memory/v1/admin/documents", json={
        "source_system": "confluence", "external_id": "KB-200", "title": "VPN Runbook", "document_type": "RUNBOOK",
        "raw_content": "SERVICE: vpn-auth is affected by SYMPTOM: mfa-loop-after-reset.",
        "ingested_by": "admin-1", "extract_graph": True,
    })
    assert ingested.status_code == 201
    assert ingested.json()["ingestion_status"] == "ACTIVE"

    # Reingesting the same source_system/external_id/version is rejected.
    duplicate = client.post("/internal/memory/v1/admin/documents", json={
        "source_system": "confluence", "external_id": "KB-200", "title": "VPN Runbook", "document_type": "RUNBOOK",
        "raw_content": "same content again", "ingested_by": "admin-1",
    })
    assert duplicate.status_code == 409
    assert duplicate.json()["error"]["code"] == "DOCUMENT_ALREADY_INGESTED"


def test_admin_outbox_dispatch_publishes_pending_events(client: TestClient) -> None:
    _extract_and_publish_candidate(client, text="disk full on app server")

    dispatched = client.post("/internal/memory/v1/admin/outbox/dispatch", headers={"X-Actor-Id": "ops-1"})
    assert dispatched.status_code == 200
    body = dispatched.json()
    assert body["scanned"] >= 1
    assert body["published"] == body["scanned"]
    assert body["failed"] == 0
    assert body["dead_lettered"] == 0
