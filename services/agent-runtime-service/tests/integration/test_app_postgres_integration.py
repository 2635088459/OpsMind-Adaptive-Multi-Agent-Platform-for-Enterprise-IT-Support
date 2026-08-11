"""SPEC-ARO-002/003 acceptance-criteria: same acceptance walk as
tests/test_app.py, but wired to the real, migrated Postgres schema instead of
SPEC-ARO-001's in-memory adapters — proves the schema baseline is not just
created, but actually load-bearing for the whole request lifecycle.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from agentruntime.container import get_container
from agentruntime.main import create_app
from agentruntime.settings import Settings

pytestmark = pytest.mark.integration


@pytest.fixture
def client(migrated_engine, monkeypatch: pytest.MonkeyPatch):
    url = migrated_engine.url
    settings = Settings(
        db_host=url.host, db_port=url.port, db_name=url.database, db_username=url.username, db_password=url.password,
        agent_runtime_persistence="postgres",
    )
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: settings)
    get_container.cache_clear()
    return TestClient(create_app())


def test_full_workflow_lifecycle_against_real_postgres(client: TestClient) -> None:
    ticket_id = str(uuid.uuid4())
    ticket_cycle_id = str(uuid.uuid4())
    start_body = {
        "ticket_id": ticket_id,
        "ticket_cycle_id": ticket_cycle_id,
        "workflow_definition_id": "triage-v1",
        "definition_version": 1,
        "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-1",
    }

    started = client.post("/internal/agent-runtime/v1/workflows", json=start_body)
    assert started.status_code == 201
    workflow_instance_id = started.json()["workflow_instance_id"]

    duplicate = client.post("/internal/agent-runtime/v1/workflows", json={**start_body, "idempotency_key": "start-2"})
    assert duplicate.status_code == 409

    paused = client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/pause", json={"idempotency_key": "pause-1"})
    assert paused.status_code == 200
    assert paused.json()["state"] == "PAUSED"

    duplicate_pause = client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/pause", json={"idempotency_key": "pause-1"})
    assert duplicate_pause.status_code == 200

    resumed = client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/resume", json={"idempotency_key": "resume-1"})
    assert resumed.status_code == 200

    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert claimed.status_code == 200
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    workflow_version = claimed.json()["workflow_version"]

    tool_response = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-1",
    })
    assert tool_response.status_code == 200
    assert tool_response.json()["status"] == "DISPATCHED"

    completed = client.post(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "complete-1", "workflow_version": workflow_version,
        "result_payload": "diagnostics collected",
    })
    assert completed.status_code == 200
    assert completed.json()["state"] == "COMPLETED"

    # SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6: "collect"
    # was this workflow's only task, so completing it also settled the whole graph — the
    # Workflow Instance itself auto-transitioned to COMPLETED, no admin action needed.
    recovered = client.post(f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/recover", headers={"X-Actor-Id": "ops-user-1"})
    assert recovered.status_code == 200
    body = recovered.json()
    assert body["state"] == "COMPLETED"
    # SPEC-ARO-005 writes a STARTED checkpoint on start, request-tool writes a
    # PRE_TOOL_CALL checkpoint, and SPEC-ARO-008 writes an AFTER_TASK checkpoint on
    # completion.
    assert body["recoverable_checkpoint_count"] == 3
    assert body["open_lease_count"] == 0

    dispatched = client.post("/internal/agent-runtime/v1/admin/outbox/dispatch", headers={"X-Actor-Id": "ops-user-1"})
    assert dispatched.status_code == 200
    assert dispatched.json()["published"] == dispatched.json()["scanned"]

    # already auto-completed — a new key against an already-terminal instance is a real
    # conflict, not a no-op.
    admin_complete_after_auto_complete = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/complete", json={"idempotency_key": "complete-workflow-1"},
        headers={"X-Actor-Id": "ops-user-1"},
    )
    assert admin_complete_after_auto_complete.status_code == 409


def test_admin_fail_and_cancel_workflow_persist_through_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-004: proves the terminal FAILED/CANCELLED transitions round-trip through
    the real Postgres-backed WorkflowInstanceRepository, not just the in-memory adapter.
    """
    fail_target = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-fail-1",
    })
    fail_workflow_instance_id = fail_target.json()["workflow_instance_id"]

    failed = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{fail_workflow_instance_id}/fail",
        json={"idempotency_key": "fail-1", "failure_reason": "tool exhausted retries"}, headers={"X-Actor-Id": "ops-user-1"},
    )
    assert failed.status_code == 200
    assert failed.json()["state"] == "FAILED"

    cancel_target = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-cancel-1",
    })
    cancel_workflow_instance_id = cancel_target.json()["workflow_instance_id"]

    cancelled = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{cancel_workflow_instance_id}/cancel",
        json={"idempotency_key": "cancel-1", "reason": "ticket cancelled upstream"}, headers={"X-Actor-Id": "ops-user-1"},
    )
    assert cancelled.status_code == 200
    assert cancelled.json()["state"] == "CANCELLED"

    # A brand new Container instance reading the same rows sees the persisted terminal state.
    get_container.cache_clear()
    fresh_client = TestClient(create_app())
    recovered = fresh_client.post(f"/internal/agent-runtime/v1/admin/workflows/{cancel_workflow_instance_id}/recover")
    assert recovered.status_code == 200
    assert recovered.json()["state"] == "CANCELLED"


def test_workflow_survives_a_fresh_container_process(migrated_engine, monkeypatch: pytest.MonkeyPatch) -> None:
    """SPEC-ARO-001's in-memory adapters lose everything on process restart; this is
    the one behavior only a real database can prove: state written by one Container
    instance is readable by a brand new one against the same database.
    """
    url = migrated_engine.url
    settings = Settings(
        db_host=url.host, db_port=url.port, db_name=url.database, db_username=url.username, db_password=url.password,
        agent_runtime_persistence="postgres",
    )
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: settings)

    get_container.cache_clear()
    first_process_client = TestClient(create_app())
    started = first_process_client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    # Simulate a process restart: a brand new Container, same database.
    get_container.cache_clear()
    second_process_client = TestClient(create_app())
    recovered = second_process_client.post(f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/recover")

    assert recovered.status_code == 200
    assert recovered.json()["workflow_instance_id"] == workflow_instance_id


def test_ticket_created_starts_a_workflow_instance_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-005 04-use-cases UC-01, proved against the real migrated schema: the
    STARTED checkpoint and the initial task graph both actually persist, not just the
    in-memory adapters.
    """
    body = {
        "event_id": "evt-1", "event_type": "ticket.created.v1", "producer": "ticket-workflow-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()),
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()),
        "priority": "HIGH", "category": "network_outage", "created_by": "ticket-workflow-service",
        "occurred_at": "2026-01-01T00:00:00Z",
    }

    response = client.post("/internal/agent-runtime/v1/events/ticket-created", json=body)
    assert response.status_code == 200
    assert response.json() == {"eventId": "evt-1", "applied": True}

    replay = client.post("/internal/agent-runtime/v1/events/ticket-created", json=body)
    assert replay.status_code == 200
    assert replay.json()["applied"] is False


def test_query_api_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-006 05-api-contracts "Query API", proved against the real migrated
    schema — including the new ticket_id index and the checkpoints
    (workflow_instance_id, created_at) index the "latest" query relies on.
    """
    ticket_id = str(uuid.uuid4())
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": ticket_id, "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-query-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    by_id = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert by_id.status_code == 200
    assert by_id.json()["state"] == "RUNNING"

    by_ticket = client.get(f"/internal/agent-runtime/v1/workflows/by-ticket/{ticket_id}")
    assert by_ticket.status_code == 200
    assert [w["workflow_instance_id"] for w in by_ticket.json()] == [workflow_instance_id]

    latest_checkpoint = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert latest_checkpoint.status_code == 200
    assert latest_checkpoint.json()["type"] == "STARTED"

    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]

    task_by_id = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_by_id.status_code == 200
    assert task_by_id.json()["state"] == "CLAIMED"

    missing_task = client.get(f"/internal/agent-runtime/v1/agent-tasks/{uuid.uuid4()}")
    assert missing_task.status_code == 404


def test_completing_a_task_unlocks_its_downstream_dependent_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-008 04-use-cases UC-02 steps 5-6, proved against the real migrated schema
    (including the new checkpoints (workflow_instance_id, checkpoint_type) index the
    STARTED-checkpoint lookup relies on) — a two-task graph with a dependency must not
    get stuck after its first task completes.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [
            {"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"},
            {"task_key": "remediate", "task_type": "apply_fix", "depends_on": ["collect"], "join_policy": "ALL_SUCCESS"},
        ],
        "idempotency_key": "start-unlock-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    remediate_before = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "remediate", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert remediate_before.status_code == 404

    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    collect_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    workflow_version = claimed.json()["workflow_version"]

    completed = client.post(f"/internal/agent-runtime/v1/agent-tasks/{collect_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "complete-collect-pg-1", "workflow_version": workflow_version,
        "result_payload": "diagnostics collected",
    })
    assert completed.status_code == 200

    remediate_after = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "remediate", "worker_id": "worker-2", "lease_seconds": 60,
    })
    assert remediate_after.status_code == 200
    assert remediate_after.json()["state"] == "CLAIMED"

    latest_checkpoint = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert latest_checkpoint.json()["type"] == "AFTER_TASK"


def _claim_and_complete(
    client: TestClient, workflow_instance_id: str, task_key: str, worker_id: str, idempotency_key: str,
    *, result_payload: str | None = None, failure_reason: str | None = None,
):
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": task_key, "worker_id": worker_id, "lease_seconds": 60,
    })
    assert claimed.status_code == 200
    return client.post(f"/internal/agent-runtime/v1/agent-tasks/{claimed.json()['agent_task_id']}/complete", json={
        "claim_token": claimed.json()["claim_token"], "idempotency_key": idempotency_key,
        "workflow_version": claimed.json()["workflow_version"], "result_payload": result_payload, "failure_reason": failure_reason,
    })


def test_a_multi_task_workflow_auto_completes_only_once_every_task_succeeds_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6, proved
    against the real migrated schema.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [
            {"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"},
            {"task_key": "remediate", "task_type": "apply_fix", "depends_on": ["collect"], "join_policy": "ALL_SUCCESS"},
        ],
        "idempotency_key": "start-multi-complete-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    collect_completed = _claim_and_complete(
        client, workflow_instance_id, "collect", "worker-1", "complete-collect-pg-1", result_payload="diagnostics collected"
    )
    assert collect_completed.status_code == 200

    mid_flight = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert mid_flight.json()["state"] == "RUNNING"

    remediate_completed = _claim_and_complete(
        client, workflow_instance_id, "remediate", "worker-2", "complete-remediate-pg-1", result_payload="fix applied"
    )
    assert remediate_completed.status_code == 200

    settled = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert settled.json()["state"] == "COMPLETED"


def test_a_multi_task_workflow_auto_fails_when_a_task_fails_against_real_postgres(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [
            {"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"},
            {"task_key": "remediate", "task_type": "apply_fix", "depends_on": ["collect"], "join_policy": "ALL_SUCCESS"},
        ],
        "idempotency_key": "start-multi-fail-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    collect_failed = _claim_and_complete(
        client, workflow_instance_id, "collect", "worker-1", "complete-collect-pg-1", failure_reason="diagnostics tool crashed"
    )
    assert collect_failed.status_code == 200
    assert collect_failed.json()["state"] == "FAILED_FINAL"

    settled = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert settled.json()["state"] == "FAILED"

    remediate_claim = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "remediate", "worker_id": "worker-2", "lease_seconds": 60,
    })
    assert remediate_claim.status_code == 409
