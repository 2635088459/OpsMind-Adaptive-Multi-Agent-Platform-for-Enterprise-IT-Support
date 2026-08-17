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
from memoryknowledge.settings import Settings

pytestmark = pytest.mark.unit


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    # SPEC-MK-002 made "postgres" the container's real-run default (Settings.
    # memory_persistence) — this test stays on SPEC-MK-001's in-memory adapters (fast,
    # hermetic, no Docker); see tests/integration/test_postgres_repositories.py for the
    # same kind of coverage against a real Postgres.
    monkeypatch.setattr("memoryknowledge.container.get_settings", lambda: Settings(memory_persistence="memory"))
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


def test_search_response_carries_degraded_reason_and_graph_degraded_fields(client: TestClient) -> None:
    """SPEC-MK-020 10-failure-handling §"Retrieval Degraded"/"Graph Failure"."""
    published = _extract_and_publish_candidate(client, text="printer offline root cause confirmed")
    memory_id = published["memory_id"]

    search = client.post("/internal/memory/v1/search", json={
        "query": "printer offline root cause confirmed", "requester_type": "agent", "requester_id": "knowledge-agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    assert search.status_code == 200
    body = search.json()
    assert body["degraded"] is False
    assert body["degraded_reason"] is None
    assert body["graph_degraded"] is False
    assert any(r["source_id"] == memory_id for r in body["results"])


def test_reject_candidate_publishes_rejected_event_and_never_reaches_search(client: TestClient) -> None:
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-2"}],
        "candidate_text": "unverified rumor about outage", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]

    rejected = client.post(
        f"/internal/memory/v1/admin/candidates/{candidate_id}/reject", json={"reason": "unverified"}, headers={"X-Actor-Id": "reviewer-1"},
    )
    assert rejected.status_code == 200
    assert rejected.json()["status"] == "REJECTED"

    # A rejected candidate can never be approved afterward.
    approve_after_reject = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.5, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "x", "summary": "x", "source_trust_score": 0.5,
    })
    assert approve_after_reject.status_code == 409
    assert approve_after_reject.json()["error"]["code"] == "INVALID_STATE_TRANSITION"


def test_ticket_resolved_event_extracts_a_candidate_that_can_be_validated_and_published(client: TestClient) -> None:
    """SPEC-MK-010 06-event-contracts (02-ticket-workflow PUB-012 "ticket.resolved.v1")
    end-to-end into SPEC-MK-011/012's own existing validate/approve pipeline.
    """
    ticket_id, ticket_cycle_id = str(uuid.uuid4()), str(uuid.uuid4())
    ingested = client.post("/internal/memory/v1/events/ticket-resolved", json={
        "event_id": f"evt-{uuid.uuid4()}", "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id,
        "resolution_code": "MFA_RESET_SUCCESSFUL", "resolution_summary": "reset device binding fixed the mfa loop",
        "resolved_by": "verification-agent", "resolved_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    })
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    audit_events = client.get("/internal/memory/v1/admin/audit-events", headers={"X-Actor-Id": "ops-1"})
    [created_entry] = [e for e in audit_events.json() if e["action"] == "extract_candidate"]
    candidate_id = created_entry["resource_id"]

    validated = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={
        "source_refs_trusted": True, "confidence_score": 0.9,
    })
    assert validated.status_code == 200
    assert validated.json()["status"] == "VALIDATED"

    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.8, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "vpn mfa loop resolved by resetting device binding", "summary": "vpn mfa loop resolved",
        "source_trust_score": 0.9,
    })
    assert approved.status_code == 200
    assert approved.json()["status"] == "ACTIVE"


def test_ticket_resolved_event_replay_under_the_same_event_id_is_not_applied_twice(client: TestClient) -> None:
    body = {
        "event_id": "evt-replay-1", "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()),
        "resolution_code": "MFA_RESET_SUCCESSFUL", "resolution_summary": "reset device binding fixed the mfa loop",
        "resolved_by": "verification-agent", "resolved_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    }
    first = client.post("/internal/memory/v1/events/ticket-resolved", json=body)
    second = client.post("/internal/memory/v1/events/ticket-resolved", json=body)

    assert first.json()["applied"] is True
    assert second.json()["applied"] is False


def test_workflow_completed_and_failed_events_extract_their_own_candidates(client: TestClient) -> None:
    """SPEC-MK-022 06-event-contracts (03-agent-runtime-orchestration
    "workflow.completed.v1"/"workflow.failed.v1")."""
    completed = client.post("/internal/memory/v1/events/workflow-completed", json={
        "event_id": f"evt-{uuid.uuid4()}", "workflow_instance_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "from_state": "IN_PROGRESS", "to_state": "COMPLETED", "workflow_version": 3,
        "occurred_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    })
    assert completed.status_code == 200
    assert completed.json()["applied"] is True

    failed = client.post("/internal/memory/v1/events/workflow-failed", json={
        "event_id": f"evt-{uuid.uuid4()}", "workflow_instance_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "from_state": "IN_PROGRESS", "to_state": "FAILED", "workflow_version": 2, "failure_reason": "tool gateway timeout",
        "occurred_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    })
    assert failed.status_code == 200
    assert failed.json()["applied"] is True

    audit_events = client.get("/internal/memory/v1/admin/audit-events", headers={"X-Actor-Id": "ops-1"})
    extract_actions = [e for e in audit_events.json() if e["action"] == "extract_candidate"]
    assert len(extract_actions) == 2


def test_workflow_completed_event_replay_under_the_same_event_id_is_not_applied_twice(client: TestClient) -> None:
    body = {
        "event_id": "evt-wf-replay-1", "workflow_instance_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "from_state": "IN_PROGRESS", "to_state": "COMPLETED", "workflow_version": 1,
        "occurred_at": "2026-08-16T00:00:00Z", "correlation_id": str(uuid.uuid4()),
    }
    first = client.post("/internal/memory/v1/events/workflow-completed", json=body)
    second = client.post("/internal/memory/v1/events/workflow-completed", json=body)

    assert first.json()["applied"] is True
    assert second.json()["applied"] is False


def test_publishing_a_conflicting_candidate_requires_an_authorized_actor(client: TestClient) -> None:
    """02-business-invariants: "CONFLICTING candidate 必须人工或 policy 处理，不能自动覆盖
    active memory."
    """
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-conflict"}],
        "candidate_text": "printer jam root cause disputed", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    validated = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={
        "source_refs_trusted": True, "confidence_score": 0.8, "conflict_set_id": "conflict-set-1",
    })
    assert validated.json()["status"] == "CONFLICTING"

    blocked = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.6, "published_by": "   ", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "x", "summary": "x", "source_trust_score": 0.6,
    })
    assert blocked.status_code == 409
    assert blocked.json()["error"]["code"] == "MEMORY_CANDIDATE_CONFLICTING"

    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.6, "published_by": "reviewer-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "x", "summary": "x", "source_trust_score": 0.6,
    })
    assert approved.status_code == 200
    assert approved.json()["status"] == "ACTIVE"


def test_publishing_against_an_existing_memory_id_supersedes_the_active_version(client: TestClient) -> None:
    """UC-05 step 1 "创建 Memory 或定位 existing Memory"; 08-transaction-and-outbox
    §"Publish Memory Transaction" steps 3-4.
    """
    first = _extract_and_publish_candidate(client, text="vpn outage root cause under investigation")
    memory_id = first["memory_id"]

    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-1"}],
        "candidate_text": "vpn outage confirmed root cause: stale certificate", "idempotency_key": f"extract-{uuid.uuid4()}",
        "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.9})

    second = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.9, "published_by": "admin-2", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "vpn outage confirmed root cause: stale certificate", "summary": "vpn outage confirmed root cause: stale certificate",
        "source_trust_score": 0.95, "memory_id": memory_id,
    })
    assert second.status_code == 200
    assert second.json()["memory_id"] == memory_id
    assert second.json()["version"] == 2
    assert second.json()["status"] == "ACTIVE"

    # Only the new, superseding content is returned by search now.
    search = client.post("/internal/memory/v1/search", json={
        "query": "vpn outage", "requester_type": "agent", "requester_id": "agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    matches = [r for r in search.json()["results"] if r["source_id"] == memory_id]
    assert matches and all(r["source_version"] == 2 for r in matches)


def test_restricted_classification_memory_is_hidden_from_a_plain_agent_but_visible_to_admin(client: TestClient) -> None:
    """SPEC-MK-025 02-business-invariants: "高敏 classification 的 memory 默认不可跨
    queue / role 检索"; 11-security §"检索前必须计算 access scope，并应用到：Memory
    classification."
    """
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-1"}],
        "candidate_text": "salary adjustment approved for employee X", "idempotency_key": f"extract-{uuid.uuid4()}",
        "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.9})
    approved = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.7, "published_by": "admin-1", "idempotency_key": f"publish-{uuid.uuid4()}",
        "content": "salary adjustment approved for employee X", "summary": "salary adjustment approved for employee X",
        "source_trust_score": 0.9, "classification": "RESTRICTED",
    })
    assert approved.status_code == 200
    memory_id = approved.json()["memory_id"]

    agent_search = client.post("/internal/memory/v1/search", json={
        "query": "salary adjustment", "requester_type": "agent", "requester_id": "agent-1",
        "access_scope": {"tenant": "acme", "role": "agent", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    assert not any(r["source_id"] == memory_id for r in agent_search.json()["results"])

    admin_search = client.post("/internal/memory/v1/search", json={
        "query": "salary adjustment", "requester_type": "agent", "requester_id": "admin-agent-1",
        "access_scope": {"tenant": "acme", "role": "admin", "classification": "INTERNAL"}, "correlation_id": str(uuid.uuid4()),
    })
    assert any(r["source_id"] == memory_id for r in admin_search.json()["results"])


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
        "expected_version": 0, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()), "add_facts": ["vpn down"],
    })
    assert created.status_code == 200
    assert created.json()["facts"] == ["vpn down"]
    current_version = created.json()["version"]

    stale_update = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": 0, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()), "add_facts": ["stale write"],
    })
    assert stale_update.status_code == 409
    assert stale_update.json()["error"]["code"] == "WORKING_MEMORY_VERSION_CONFLICT"

    updated = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": current_version, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()), "add_facts": ["mfa reset needed"],
    })
    assert updated.status_code == 200
    assert set(updated.json()["facts"]) == {"vpn down", "mfa reset needed"}


def test_working_memory_response_exposes_the_full_aggregate_and_redacts_secrets(client: TestClient) -> None:
    """SPEC-MK-004 01-domain-model §"WorkingMemory": every aggregate field is
    projected in the response (rejected_hypotheses/tool_evidence_refs/
    approval_decision_refs were missing from the view since SPEC-MK-001), and its own
    约束: "raw secret、完整凭据、未脱敏工具输出不能进入正文."
    """
    ticket_id, ticket_cycle_id, workflow_instance_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    derived_id = str(derive_working_memory_id(TicketId(uuid.UUID(ticket_id)), TicketCycleId(uuid.UUID(ticket_cycle_id)), WorkflowInstanceId(uuid.UUID(workflow_instance_id))))

    created = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": 0, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()),
        "add_facts": ["contact user@example.com for follow-up"],
        "add_hypotheses": ["bad cable", "api_key: abcd1234efgh5678 may be leaked"],
        "add_tool_evidence_refs": [{"tool_request_id": "tool-1", "summary": "token: super-secret-value", "status": "COMPLETED", "evidence_hash": "hash-1"}],
        "add_approval_decision_refs": ["approval-1"],
    })
    assert created.status_code == 200
    body = created.json()
    assert "user@example.com" not in " ".join(body["facts"])
    assert "abcd1234efgh5678" not in " ".join(body["hypotheses"])
    assert "super-secret-value" not in body["tool_evidence_refs"][0]["summary"]
    assert body["approval_decision_refs"] == ["approval-1"]

    rejected = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": body["version"], "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()),
        "reject_hypotheses": [{"hypothesis": "bad cable", "reason": "cable tested fine"}],
    })
    assert rejected.status_code == 200
    rejected_body = rejected.json()
    assert "bad cable" not in rejected_body["hypotheses"]
    assert rejected_body["rejected_hypotheses"] == [{"hypothesis": "bad cable", "reason": "cable tested fine", "rejected_at": rejected_body["rejected_hypotheses"][0]["rejected_at"]}]


def test_working_memory_path_id_must_match_derived_scope_id(client: TestClient) -> None:
    wrong_id = str(uuid.uuid4())
    response = client.patch(f"/internal/memory/v1/working-memory/{wrong_id}", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_instance_id": str(uuid.uuid4()),
        "expected_version": 0, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()),
    })
    assert response.status_code == 400


def test_working_memory_query_archive_and_delete_lifecycle(client: TestClient) -> None:
    """SPEC-MK-006 05-api-contracts: `GET .../working-memory/{id}`, `POST .../archive`,
    `POST .../delete` — full lifecycle: create -> query -> archive -> update-after-
    archive fails -> delete -> a second delete fails.
    """
    ticket_id, ticket_cycle_id, workflow_instance_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
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
    assert queried.json()["version"] == current_version

    missing = client.get(f"/internal/memory/v1/working-memory/{uuid.uuid4()}", params={"correlation_id": str(uuid.uuid4())})
    assert missing.status_code == 404

    archived = client.post(
        f"/internal/memory/v1/admin/working-memory/{derived_id}/archive",
        json={"expected_version": current_version, "correlation_id": str(uuid.uuid4())}, headers={"X-Actor-Id": "ops-1"},
    )
    assert archived.status_code == 200
    assert archived.json()["status"] == "ARCHIVED"
    archived_version = archived.json()["version"]

    stale_archive = client.post(
        f"/internal/memory/v1/admin/working-memory/{derived_id}/archive",
        json={"expected_version": current_version, "correlation_id": str(uuid.uuid4())}, headers={"X-Actor-Id": "ops-1"},
    )
    assert stale_archive.status_code == 409

    update_after_archive = client.patch(f"/internal/memory/v1/working-memory/{derived_id}", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_instance_id": workflow_instance_id,
        "expected_version": archived_version, "updated_by": "agent-1", "correlation_id": str(uuid.uuid4()), "add_facts": ["late write"],
    })
    assert update_after_archive.status_code == 409

    archive_requires_actor = client.post(
        f"/internal/memory/v1/admin/working-memory/{derived_id}/archive",
        json={"expected_version": archived_version, "correlation_id": str(uuid.uuid4())},
    )
    assert archive_requires_actor.status_code == 400

    deleted = client.post(
        f"/internal/memory/v1/admin/working-memory/{derived_id}/delete",
        json={"expected_version": archived_version, "correlation_id": str(uuid.uuid4())}, headers={"X-Actor-Id": "ops-1"},
    )
    assert deleted.status_code == 200
    assert deleted.json()["status"] == "DELETED"
    assert deleted.json()["facts"] == []
    deleted_version = deleted.json()["version"]

    second_delete = client.post(
        f"/internal/memory/v1/admin/working-memory/{derived_id}/delete",
        json={"expected_version": deleted_version, "correlation_id": str(uuid.uuid4())}, headers={"X-Actor-Id": "ops-1"},
    )
    assert second_delete.status_code == 409


def test_ingest_document_with_graph_extraction_and_admin_node_lookup(client: TestClient) -> None:
    ingested = client.post("/internal/memory/v1/admin/documents", json={
        "source_system": "confluence", "external_id": "KB-200", "title": "VPN Runbook", "document_type": "RUNBOOK",
        "raw_content": "SERVICE: vpn-auth is affected by SYMPTOM: mfa-loop-after-reset.",
        "ingested_by": "admin-1", "extract_graph": True,
    })
    assert ingested.status_code == 201
    assert ingested.json()["ingestion_status"] == "ACTIVE"

    # Reingesting the same source_system/external_id/version with *different* content
    # is a genuine identity conflict.
    duplicate = client.post("/internal/memory/v1/admin/documents", json={
        "source_system": "confluence", "external_id": "KB-200", "title": "VPN Runbook", "document_type": "RUNBOOK",
        "raw_content": "same content again", "ingested_by": "admin-1",
    })
    assert duplicate.status_code == 409
    assert duplicate.json()["error"]["code"] == "DOCUMENT_ALREADY_INGESTED"


def test_reingesting_the_identical_document_replays_the_prior_result(client: TestClient) -> None:
    """SPEC-MK-009 09-concurrency-and-idempotency §"Document Reingestion": "相同
    document version 重复导入是幂等成功."
    """
    request = {
        "source_system": "confluence", "external_id": "KB-201", "title": "Printer Runbook", "document_type": "RUNBOOK",
        "raw_content": "printer offline: reseat the network cable.", "ingested_by": "admin-1",
    }
    first = client.post("/internal/memory/v1/admin/documents", json=request)
    assert first.status_code == 201

    replay = client.post("/internal/memory/v1/admin/documents", json=request)
    assert replay.status_code == 201
    assert replay.json()["document_id"] == first.json()["document_id"]
    assert replay.json()["chunk_count"] == first.json()["chunk_count"]


def test_admin_outbox_dispatch_publishes_pending_events(client: TestClient) -> None:
    _extract_and_publish_candidate(client, text="disk full on app server")

    dispatched = client.post("/internal/memory/v1/admin/outbox/dispatch", headers={"X-Actor-Id": "ops-1"})
    assert dispatched.status_code == 200
    body = dispatched.json()
    assert body["scanned"] >= 1
    assert body["published"] == body["scanned"]
    assert body["failed"] == 0
    assert body["dead_lettered"] == 0


def test_reusing_an_idempotency_key_with_a_different_payload_conflicts(client: TestClient) -> None:
    """SPEC-MK-003 09-concurrency-and-idempotency §"Command Idempotency": "同一 key with
    different request hash must return conflict."
    """
    extracted = client.post("/internal/memory/v1/admin/candidates", json={
        "memory_type": "EPISODIC", "source_refs": [{"source_type": "ticket", "source_id": "T-9"}],
        "candidate_text": "printer offline", "idempotency_key": f"extract-{uuid.uuid4()}", "extracted_by": "agent-1",
    })
    candidate_id = extracted.json()["candidate_id"]
    client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/validate", json={"source_refs_trusted": True, "confidence_score": 0.8})

    shared_key = f"publish-{uuid.uuid4()}"
    first = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.6, "published_by": "admin-1", "idempotency_key": shared_key,
        "content": "printer offline", "summary": "printer offline", "source_trust_score": 0.8,
    })
    assert first.status_code == 200

    # Retried delivery, same key, same payload: replays the cached response.
    replay = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.6, "published_by": "admin-1", "idempotency_key": shared_key,
        "content": "printer offline", "summary": "printer offline", "source_trust_score": 0.8,
    })
    assert replay.status_code == 200
    assert replay.json()["memory_version_id"] == first.json()["memory_version_id"]

    # Same key, different payload: conflict, not a silent replay of the old result.
    conflicting = client.post(f"/internal/memory/v1/admin/candidates/{candidate_id}/approve", json={
        "usefulness_score": 0.9, "published_by": "admin-1", "idempotency_key": shared_key,
        "content": "printer offline - different content", "summary": "printer offline", "source_trust_score": 0.8,
    })
    assert conflicting.status_code == 409
    assert conflicting.json()["error"]["code"] == "IDEMPOTENCY_KEY_REUSED"


def test_admin_audit_events_records_every_named_action(client: TestClient) -> None:
    """SPEC-MK-003 12-observability §"Audit Events" §"审计动作"."""
    published = _extract_and_publish_candidate(client, text="vpn login fails after mfa reset yet again")
    memory_id = published["memory_id"]
    client.post(f"/internal/memory/v1/admin/memories/{memory_id}/deprecate", json={"idempotency_key": f"dep-{uuid.uuid4()}"}, headers={"X-Actor-Id": "ops-1"})

    audit_events = client.get("/internal/memory/v1/admin/audit-events", headers={"X-Actor-Id": "ops-1"})
    assert audit_events.status_code == 200
    actions = {entry["action"] for entry in audit_events.json()}
    assert {"extract_candidate", "approve_candidate", "publish_memory", "deprecate_memory"} <= actions


def test_admin_audit_events_requires_actor_header(client: TestClient) -> None:
    response = client.get("/internal/memory/v1/admin/audit-events")
    assert response.status_code == 400


def test_admin_outbox_replay_dead_letter_republishes_a_dead_lettered_event(client: TestClient) -> None:
    """SPEC-MK-003 08-transaction-and-outbox §"Outbox Publisher": "replay 必须幂等." No HTTP
    path can drive a real publish failure (LoggingEventPublisherAdapter always
    succeeds), so a DEAD_LETTER row is seeded directly on the container's repository —
    mirrors agent-runtime-service's own equivalent test.
    """
    from memoryknowledge.application.outbox_codec import build_outbox_record
    from memoryknowledge.container import get_container
    from memoryknowledge.domain.events import MemoryDeleted

    container = get_container()
    now = container.clock.now()
    dead_lettered = build_outbox_record(
        MemoryDeleted(source_type="MEMORY", source_id=str(uuid.uuid4()), occurred_at=now),
        "memory.deleted.v1", aggregate_id="agg-replay-1", occurred_at=now,
    )
    container.outbox_repository.append(dead_lettered)
    container.outbox_repository.mark_dead_letter(dead_lettered.outbox_id)

    replayed = client.post("/internal/memory/v1/admin/outbox/replay-dead-letter", headers={"X-Actor-Id": "ops-1"})
    assert replayed.status_code == 200
    body = replayed.json()
    assert body["scanned"] >= 1
    assert body["published"] >= 1
    assert body["dead_lettered"] == 0

    [record] = [r for r in container.outbox_repository.recorded() if r.outbox_id == dead_lettered.outbox_id]
    assert record.status.name == "PUBLISHED"
    assert record.attempts == 0
