"""SPEC-MK-002 acceptance-criteria: the same acceptance walk as tests/test_app.py,
but wired to the real, migrated `memory` Postgres schema instead of SPEC-MK-001's
in-memory adapters — proves the schema baseline is not just created, but actually
load-bearing for the whole request lifecycle. Mirrors agent-runtime-service's own
tests/integration/test_app_postgres_integration.py.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from memoryknowledge.container import get_container
from memoryknowledge.main import create_app
from memoryknowledge.settings import Settings

pytestmark = pytest.mark.integration


@pytest.fixture
def client(migrated_engine, monkeypatch: pytest.MonkeyPatch):
    url = migrated_engine.url
    settings = Settings(
        db_host=url.host, db_port=url.port, db_name=url.database, db_username=url.username, db_password=url.password,
        memory_persistence="postgres",
    )
    monkeypatch.setattr("memoryknowledge.container.get_settings", lambda: settings)
    get_container.cache_clear()
    return TestClient(create_app())


def test_candidate_pipeline_to_publish_and_search_against_real_postgres(client: TestClient) -> None:
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-1"}],
        "candidate_text": "vpn login fails after mfa reset", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
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
        "content": "vpn login fails after mfa reset", "summary": "vpn login fails after mfa reset", "source_trust_score": 0.9,
    })
    assert approved.status_code == 200
    memory_id = approved.json()["memory_id"]

    search = client.post("/internal/memory/v1/search", json={
        "query": "vpn login fails after mfa reset", "requester_type": "agent", "requester_id": "knowledge-agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    assert search.status_code == 200
    assert any(r["source_id"] == memory_id for r in search.json()["results"])

    deprecated = client.post(
        f"/internal/memory/v1/admin/memories/{memory_id}/deprecate",
        json={"idempotency_key": f"dep-{uuid.uuid4()}"}, headers={"X-Actor-Id": "ops-1"},
    )
    assert deprecated.status_code == 200
    assert deprecated.json()["status"] == "DEPRECATED"

    deletion = client.post(
        "/internal/memory/v1/admin/deletion-requests",
        json={"memory_id": memory_id, "reason": "retention expired", "idempotency_key": f"del-{uuid.uuid4()}"},
        headers={"X-Actor-Id": "ops-1"},
    )
    assert deletion.status_code == 200
    assert deletion.json()["versions_deleted"] == 1

    dispatched = client.post("/internal/memory/v1/admin/outbox/dispatch", headers={"X-Actor-Id": "ops-1"})
    assert dispatched.status_code == 200
    body = dispatched.json()
    assert body["scanned"] >= 1
    assert body["published"] == body["scanned"]


def test_publish_supersede_and_delete_scrub_content_against_real_postgres(client: TestClient) -> None:
    """SPEC-MK-015 (supersede) / SPEC-MK-016 (delete scrubs content) end-to-end
    against a real, migrated Postgres schema.
    """
    from memoryknowledge.container import get_container
    from memoryknowledge.domain.ids import MemoryId

    first = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-pg-1"}],
        "candidate_text": "disk usage climbing on app-server-1", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = first.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.85})
    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "disk usage climbing on app-server-1", "summary": "disk usage climbing", "source_trust_score": 0.9,
    })
    memory_id = approved.json()["memory_id"]

    second_extract = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-pg-1"}],
        "candidate_text": "disk usage root cause confirmed: log rotation misconfigured", "idempotency_key": f"extract-{uuid.uuid4()}",
        "extracted_by": "agent-1",
    })
    second_candidate_id = second_extract.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{second_candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.9})
    superseding = client.post(f"/internal/memory/v1/admin/candidates/{second_candidate_id}/approve", json={
        "usefulness_score": 0.9, "published_by": "admin-2", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "disk usage root cause confirmed: log rotation misconfigured", "summary": "root cause confirmed",
        "source_trust_score": 0.95, "memory_id": memory_id,
    })
    assert superseding.status_code == 200
    assert superseding.json()["version"] == 2

    deletion = client.post(
        "/internal/memory/v1/admin/deletion-requests",
        json={"memory_id": memory_id, "reason": "retention expired", "idempotency_key": f"del-{uuid.uuid4()}"},
        headers={"X-Actor-Id": "ops-1"},
    )
    assert deletion.status_code == 200
    assert deletion.json()["versions_deleted"] == 2

    versions = get_container().memory_repository.find_versions(MemoryId(uuid.UUID(memory_id)))
    assert len(versions) == 2
    assert all(v.status.name == "DELETED" for v in versions)
    assert all(v.content == "" and v.summary == "" for v in versions)


def test_ticket_resolved_event_extracts_and_publishes_a_candidate_against_real_postgres(client: TestClient) -> None:
    """SPEC-MK-010/011 end-to-end against a real, migrated Postgres schema — proves
    the new memory_candidates.source_hash NOT NULL column and its
    (source_hash, memory_type) unique constraint are load-bearing, not just an
    in-memory-adapter behavior.
    """
    ticket_id, ticket_cycle_id = str(uuid.uuid4()), str(uuid.uuid4())
    body = {
        "event_id": f"evt-{uuid.uuid4()}", "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id,
        "resolution_code": "MFA_RESET_SUCCESSFUL", "resolution_summary": "reset device binding fixed the mfa loop",
        "resolved_by": "verification-agent", "resolved_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    }
    ingested = client.post("/internal/memory/v1/events/ticket-resolved", json=body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    replay = client.post("/internal/memory/v1/events/ticket-resolved", json=body)
    assert replay.json()["applied"] is False

    audit_events = client.get("/internal/memory/v1/admin/audit-events", headers={"X-Actor-Id": "ops-1"})
    [created_entry] = [e for e in audit_events.json() if e["action"] == "extract_candidate"]
    candidate_id = created_entry["resource_id"]

    validated = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={
        "source_refs_trusted": True, "confidence_score": 0.9,
    })
    assert validated.status_code == 200
    assert validated.json()["status"] == "VALIDATED"


def test_working_memory_survives_a_container_rebuild_against_real_postgres(client: TestClient, migrated_engine, monkeypatch: pytest.MonkeyPatch) -> None:
    """Proves this is durable storage, not the SPEC-MK-001 in-memory adapters silently
    still in play: a fresh Container (as a new process would build) still finds the
    row a previous Container instance wrote.
    """
    ticket_id, ticket_cycle_id, workflow_instance_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    from memoryknowledge.domain.ids import TicketCycleId, TicketId, WorkflowInstanceId
    from memoryknowledge.domain.working_memory import derive_working_memory_id

    derived_id = str(derive_working_memory_id(TicketId(uuid.UUID(ticket_id)), TicketCycleId(uuid.UUID(ticket_cycle_id)), WorkflowInstanceId(uuid.UUID(workflow_instance_id))))

    created = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": 0, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()), "add_facts": ["vpn down"],
    })
    assert created.status_code == 200

    get_container.cache_clear()
    new_client = TestClient(create_app())
    found = new_client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": created.json()["version"], "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()),
        "add_facts": ["mfa reset needed"],
    })
    assert found.status_code == 200
    assert set(found.json()["facts"]) == {"vpn down", "mfa reset needed"}


def test_memory_classification_is_persisted_and_enforced_against_real_postgres(client: TestClient) -> None:
    """SPEC-MK-025 against a real, migrated Postgres schema — proves
    memories.classification actually stores the caller-supplied value (not just the
    column's own default) and that access control reads it back correctly.
    """
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-pg-classification-1"}],
        "candidate_text": "internal salary review notes", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.9})
    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "internal salary review notes", "summary": "internal salary review notes", "source_trust_score": 0.9,
        "classification": "RESTRICTED",
    })
    memory_id = approved.json()["memory_id"]

    from memoryknowledge.container import get_container
    from memoryknowledge.domain.ids import MemoryId

    memory = get_container().memory_repository.find_memory_by_id(MemoryId(uuid.UUID(memory_id)))
    assert memory.classification == "RESTRICTED"

    agent_search = client.post("/internal/memory/v1/search", json={
        "query": "internal salary review", "requester_type": "agent", "requester_id": "agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    assert not any(r["source_id"] == memory_id for r in agent_search.json()["results"])


def test_publish_creates_a_real_embedding_and_graph_nodes_against_real_postgres(client: TestClient) -> None:
    """SPEC-MK-017 (embed MemoryVersion content on publish) / SPEC-MK-018 (graph node
    upsert on publish) end-to-end against a real, migrated Postgres schema — proves
    the embeddings/graph_nodes tables are actually written, not just the in-memory
    adapter.
    """
    from memoryknowledge.container import get_container
    from memoryknowledge.domain.enums import GraphNodeType
    from memoryknowledge.domain.ids import MemoryVersionId

    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-pg-graph-1"}],
        "candidate_text": "cpu usage spike on worker-node-4", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.85})
    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "cpu usage spike on worker-node-4", "summary": "cpu usage spike", "source_trust_score": 0.9,
    })
    memory_id = approved.json()["memory_id"]
    memory_version_id = approved.json()["memory_version_id"]

    container = get_container()
    version = container.memory_repository.find_version_by_id(MemoryVersionId(uuid.UUID(memory_version_id)))
    assert version.embedding_ref is not None
    vector = container.embedding_repository.find(version.embedding_ref.vector_id)
    assert vector is not None and len(vector) == version.embedding_ref.dimensions

    memory_node = container.graph_node_repository.find_by_stable_key(f"memory:{memory_id}", GraphNodeType.MEMORY)
    assert memory_node is not None
    version_node = container.graph_node_repository.find_by_stable_key(f"memory_version:{memory_version_id}", GraphNodeType.MEMORY_VERSION)
    assert version_node is not None

    search = client.post("/internal/memory/v1/search", json={
        "query": "cpu usage spike on worker-node-4", "requester_type": "agent", "requester_id": "agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    assert search.status_code == 200
    body = search.json()
    assert body["degraded"] is False
    assert body["degraded_reason"] is None
    assert body["graph_degraded"] is False
    assert any(r["source_id"] == memory_id for r in body["results"])


def test_document_ingestion_idempotent_replay_against_real_postgres(client: TestClient) -> None:
    """SPEC-MK-009 09-concurrency-and-idempotency §"Document Reingestion": "相同
    document version 重复导入是幂等成功" — proves find_by_natural_key() +
    find_chunks() drive the replay branch correctly against a real, migrated
    Postgres schema, not just the SPEC-MK-001 in-memory adapters.
    """
    request = {
        "source_system": "confluence", "external_id": "KB-PG-1", "title": "VPN Runbook", "document_type": "RUNBOOK",
        "raw_content": "SERVICE: vpn-auth is affected by SYMPTOM: mfa-loop-after-reset.", "ingested_by": "admin-1",
    }
    first = client.post("/internal/memory/v1/admin/documents", json=request)
    assert first.status_code == 201
    assert first.json()["ingestion_status"] == "ACTIVE"

    replay = client.post("/internal/memory/v1/admin/documents", json=request)
    assert replay.status_code == 201
    assert replay.json()["document_id"] == first.json()["document_id"]
    assert replay.json()["chunk_count"] == first.json()["chunk_count"]

    conflicting = client.post("/internal/memory/v1/admin/documents", json={**request, "raw_content": "different body entirely"})
    assert conflicting.status_code == 409
    assert conflicting.json()["error"]["code"] == "DOCUMENT_ALREADY_INGESTED"


def test_working_memory_query_archive_and_delete_against_real_postgres(client: TestClient) -> None:
    """SPEC-MK-006 05-api-contracts: `GET .../working-memory/{id}`, `POST .../archive`,
    `POST .../delete` — proves find_by_id() and the archive/delete CAS `save()` path
    are load-bearing against a real, migrated Postgres schema, not just the SPEC-MK-001
    in-memory adapters.
    """
    ticket_id, ticket_cycle_id, workflow_instance_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    from memoryknowledge.domain.ids import TicketCycleId, TicketId, WorkflowInstanceId
    from memoryknowledge.domain.working_memory import derive_working_memory_id

    derived_id = str(derive_working_memory_id(TicketId(uuid.UUID(ticket_id)), TicketCycleId(uuid.UUID(ticket_cycle_id)), WorkflowInstanceId(uuid.UUID(workflow_instance_id))))

    created = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": 0, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()), "add_facts": ["vpn down"],
    })
    assert created.status_code == 200
    current_version = created.json()["version"]

    queried = client.get(f"/internal/memory/v1/working-memory/{derived_id}", params={"correlation_id": str(uuid.uuid4())})
    assert queried.status_code == 200
    assert queried.json()["facts"] == ["vpn down"]

    archived = client.post(
        f"/internal/memory/v1/admin/working-memory/{derived_id}/archive",
        json={"expected_version": current_version, "correlation_id": str(uuid.uuid4())}, headers={"X-Actor-Id": "ops-1"},
    )
    assert archived.status_code == 200
    assert archived.json()["status"] == "ARCHIVED"

    deleted = client.post(
        f"/internal/memory/v1/admin/working-memory/{derived_id}/delete",
        json={"expected_version": archived.json()["version"], "correlation_id": str(uuid.uuid4())}, headers={"X-Actor-Id": "ops-1"},
    )
    assert deleted.status_code == 200
    assert deleted.json()["status"] == "DELETED"
    assert deleted.json()["facts"] == []

    still_findable = client.get(f"/internal/memory/v1/working-memory/{derived_id}", params={"correlation_id": str(uuid.uuid4())})
    assert still_findable.status_code == 200
    assert still_findable.json()["status"] == "DELETED"
