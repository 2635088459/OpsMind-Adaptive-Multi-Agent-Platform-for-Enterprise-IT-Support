"""SPEC-ARO-002/003 acceptance-criteria: same acceptance walk as
tests/test_app.py, but wired to the real, migrated Postgres schema instead of
SPEC-ARO-001's in-memory adapters — proves the schema baseline is not just
created, but actually load-bearing for the whole request lifecycle.
"""

from __future__ import annotations

import dataclasses
import json
import uuid
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from agentruntime.application.records import OutboxRecord
from agentruntime.container import get_container
from agentruntime.domain.enums import OutboxStatus, WorkflowState
from agentruntime.domain.ids import AgentTaskId, CausationId, CorrelationId, TicketId, WorkflowInstanceId
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

    # SPEC-ARO-012 08-transaction-and-outbox §"Pause Transaction" step 6: "Write PAUSED
    # checkpoint" — proved against the real migrated schema.
    pause_checkpoint = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert pause_checkpoint.json()["type"] == "PAUSE_POINT"
    assert pause_checkpoint.json()["workflow_version"] == paused.json()["workflow_version"]
    assert pause_checkpoint.json()["checksum"]

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

    # request-tool's own WAITING_TOOL/WAITING_FOR_TOOL side effects are covered by
    # test_requesting_a_tool_moves_the_task_and_workflow_into_a_waiting_state_against_real_postgres
    # below, kept deliberately separate: entering that wait is a one-way trip until
    # SPEC-ARO-020 exists, so exercising it here would derail every assertion below that
    # expects "collect" to still be a normally completable, RUNNING-workflow task.
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
    # SPEC-ARO-005 writes a STARTED checkpoint on start, SPEC-ARO-012 writes a
    # PAUSE_POINT checkpoint on pause, and SPEC-ARO-008 writes an AFTER_TASK checkpoint
    # on completion.
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
    # SPEC-ARO-011 01-domain-model: workflow_version/checksum are Checkpoint's own
    # minimal fields, proved against the real migrated schema.
    assert latest_checkpoint.json()["workflow_version"] == 1
    assert latest_checkpoint.json()["checksum"]

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


def test_requesting_a_tool_moves_the_task_and_workflow_into_a_waiting_state_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" steps 4-6,
    proved against the real migrated schema: the task moves to WAITING_TOOL, the workflow
    to WAITING_FOR_TOOL, and the Tool Request stays PENDING until
    DispatchToolRequestsService (not a synchronous ToolGatewayPort call) dispatches it.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-tool-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    tool_response = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-pg-1", "claim_token": claim_token,
    })
    assert tool_response.status_code == 200
    assert tool_response.json()["status"] == "PENDING"

    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "WAITING_TOOL"

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "WAITING_FOR_TOOL"

    dispatched = client.post("/internal/agent-runtime/v1/admin/tool-requests/dispatch", headers={"X-Actor-Id": "ops-user-1"})
    assert dispatched.status_code == 200
    assert dispatched.json()["scanned"] == 1
    assert dispatched.json()["dispatched"] == 1


def test_a_tool_completed_v1_event_wakes_the_workflow_and_completes_the_task_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-020 04-use-cases UC-04 "消费 tool.completed", proved against the real
    migrated schema: the counterpart to the previous test — task WAITING_TOOL ->
    COMPLETED, workflow WAITING_FOR_TOOL -> RUNNING -> auto-settled COMPLETED, and a
    duplicate event delivery is idempotent.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-tool-pg-2",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    tool_response = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-pg-2", "claim_token": claim_token,
    })
    tool_request_id = tool_response.json()["tool_request_id"]

    workflow_waiting = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    waiting_workflow_version = workflow_waiting.json()["workflow_version"]

    dispatched = client.post("/internal/agent-runtime/v1/admin/tool-requests/dispatch", headers={"X-Actor-Id": "ops-user-1"})
    assert dispatched.status_code == 200

    event_body = {
        "event_id": "evt-tool-pg-1", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": waiting_workflow_version,
        "occurred_at": "2026-01-01T00:00:00Z",
        "payload": json.dumps({"toolRequestId": tool_request_id, "status": "COMPLETED", "resultPayload": "diagnostics ok"}),
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "COMPLETED"

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "COMPLETED"

    checkpoints = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert checkpoints.json()["type"] == "AFTER_TASK"
    checkpoint_payload = json.loads(checkpoints.json()["payload"])
    assert checkpoint_payload["resultPayload"] == "diagnostics ok"

    duplicate = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert duplicate.status_code == 200
    assert duplicate.json()["applied"] is False


def test_a_failed_tool_completed_v1_event_fails_the_task_against_real_postgres(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-tool-pg-3",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    tool_response = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-pg-3", "claim_token": claim_token,
    })
    tool_request_id = tool_response.json()["tool_request_id"]

    workflow_waiting = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    waiting_workflow_version = workflow_waiting.json()["workflow_version"]

    event_body = {
        "event_id": "evt-tool-pg-2", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": waiting_workflow_version,
        "occurred_at": "2026-01-01T00:00:00Z",
        "payload": json.dumps({"toolRequestId": tool_request_id, "status": "FAILED", "resultPayload": "tool exhausted retries"}),
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "FAILED_FINAL"

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "FAILED"

    checkpoints = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    checkpoint_payload = json.loads(checkpoints.json()["payload"])
    assert checkpoint_payload["failureReason"] == "tool exhausted retries"


def _seed_waiting_for_approval(client: TestClient, workflow_instance_id: str) -> None:
    """SPEC-ARO-021: no spec has built an entry path into WAITING_FOR_APPROVAL yet — see
    tests/test_app.py's own _seed_waiting_for_approval for the full reasoning. Seeded
    directly through the repository, one version at a time per its CAS.
    """
    container = get_container()
    running = container.workflow_instance_repository.find_by_id(WorkflowInstanceId(uuid.UUID(workflow_instance_id)))
    container.workflow_instance_repository.save(
        dataclasses.replace(running, state=WorkflowState.WAITING_FOR_APPROVAL, workflow_version=running.workflow_version + 1)
    )


def test_an_approval_granted_v1_event_wakes_the_workflow_against_real_postgres(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-approval-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    _seed_waiting_for_approval(client, workflow_instance_id)

    event_body = {
        "event_id": "evt-approval-pg-1", "event_type": "approval.granted.v1", "producer": "approval-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z",
        "payload": json.dumps({"approvalRequestId": str(uuid.uuid4()), "decision": "APPROVED", "approvedBy": "ops-user-2"}),
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "RUNNING"

    checkpoints = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert checkpoints.json()["type"] == "RECOVERY_SNAPSHOT"

    duplicate = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert duplicate.status_code == 200
    assert duplicate.json()["applied"] is False


def test_an_approval_granted_v1_event_with_a_non_approved_decision_fails_the_workflow_against_real_postgres(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-approval-pg-2",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    _seed_waiting_for_approval(client, workflow_instance_id)

    event_body = {
        "event_id": "evt-approval-pg-2", "event_type": "approval.granted.v1", "producer": "approval-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z",
        "payload": json.dumps({"approvalRequestId": str(uuid.uuid4()), "decision": "REJECTED", "approvedBy": "ops-user-2"}),
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "FAILED"


def _seed_waiting_for_verification(client: TestClient, workflow_instance_id: str) -> None:
    container = get_container()
    running = container.workflow_instance_repository.find_by_id(WorkflowInstanceId(uuid.UUID(workflow_instance_id)))
    container.workflow_instance_repository.save(
        dataclasses.replace(running, state=WorkflowState.WAITING_FOR_VERIFICATION, workflow_version=running.workflow_version + 1)
    )


def test_a_verification_completed_v1_event_wakes_the_workflow_against_real_postgres(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-verification-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    _seed_waiting_for_verification(client, workflow_instance_id)

    event_body = {
        "event_id": "evt-verification-pg-1", "event_type": "verification.completed.v1", "producer": "verification-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z",
        "payload": json.dumps({"verificationRequestId": str(uuid.uuid4()), "passed": True, "evidence": "all checks green"}),
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "RUNNING"

    duplicate = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert duplicate.status_code == 200
    assert duplicate.json()["applied"] is False


def test_a_verification_completed_v1_event_with_passed_false_fails_the_workflow_against_real_postgres(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-verification-pg-2",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    _seed_waiting_for_verification(client, workflow_instance_id)

    event_body = {
        "event_id": "evt-verification-pg-2", "event_type": "verification.completed.v1", "producer": "verification-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z",
        "payload": json.dumps({"verificationRequestId": str(uuid.uuid4()), "passed": False, "evidence": "disk still full"}),
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "FAILED"


def test_a_ticket_cancelled_v1_event_cancels_the_active_workflow_against_real_postgres(client: TestClient) -> None:
    ticket_id, ticket_cycle_id = str(uuid.uuid4()), str(uuid.uuid4())
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-cycle-cancel-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    event_body = {
        "event_id": "evt-cycle-cancel-pg-1", "event_type": "ticket.cancelled.v1", "producer": "ticket-workflow-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id,
        "cancel_reason_code": "NO_LONGER_NEEDED", "occurred_at": "2026-01-01T00:00:00Z",
    }
    ingested = client.post("/internal/agent-runtime/v1/events/ticket-cancelled", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "CANCELLED"

    duplicate = client.post("/internal/agent-runtime/v1/events/ticket-cancelled", json=event_body)
    assert duplicate.status_code == 200
    assert duplicate.json()["applied"] is False


def test_a_ticket_reopened_v1_event_cancels_the_previous_cycles_workflow_against_real_postgres(client: TestClient) -> None:
    ticket_id, previous_cycle_id, new_cycle_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": ticket_id, "ticket_cycle_id": previous_cycle_id, "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-cycle-reopen-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    event_body = {
        "event_id": "evt-cycle-reopen-pg-1", "event_type": "ticket.reopened.v1", "producer": "ticket-workflow-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": ticket_id,
        "previous_ticket_cycle_id": previous_cycle_id, "new_ticket_cycle_id": new_cycle_id, "reason_code": "ISSUE_RECURRED",
        "occurred_at": "2026-01-01T00:00:00Z",
    }
    ingested = client.post("/internal/agent-runtime/v1/events/ticket-reopened", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "CANCELLED"


def test_a_malformed_tool_completed_v1_payload_is_recorded_as_a_poison_event_against_real_postgres(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-poison-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-poison-pg-1", "claim_token": claim_token,
    })

    event_body = {
        "event_id": "evt-poison-pg-1", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z", "payload": '{"status": "COMPLETED"',
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 422
    assert ingested.json()["error"]["code"] == "POISON_EVENT"

    poison_events = client.get("/internal/agent-runtime/v1/admin/poison-events", headers={"X-Actor-Id": "ops-user-1"})
    assert poison_events.status_code == 200
    entries = poison_events.json()["poison_events"]
    assert any(entry["event_id"] == "evt-poison-pg-1" and entry["event_type"] == "tool.completed.v1" for entry in entries)

    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "WAITING_TOOL"


def test_admin_recovery_scan_counts_a_running_workflow_and_flags_nothing_against_real_postgres(client: TestClient) -> None:
    client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-scan-pg-1",
    })

    scanned = client.post("/internal/agent-runtime/v1/admin/workflows/recovery-scan", headers={"X-Actor-Id": "ops-user-1"})
    assert scanned.status_code == 200
    body = scanned.json()
    assert body["scanned"] >= 1
    assert body["checkpoint_inconsistent"] == 0


def test_admin_recovery_scan_flags_and_fails_a_paused_workflow_with_no_pause_point_checkpoint_against_real_postgres(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-scan-pg-2",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    container = get_container()
    running = container.workflow_instance_repository.find_by_id(WorkflowInstanceId(uuid.UUID(workflow_instance_id)))
    container.workflow_instance_repository.save(
        dataclasses.replace(running, state=WorkflowState.PAUSED, pause_generation=1, workflow_version=running.workflow_version + 1)
    )

    scanned = client.post("/internal/agent-runtime/v1/admin/workflows/recovery-scan", headers={"X-Actor-Id": "ops-user-1"})
    assert scanned.status_code == 200
    assert scanned.json()["checkpoint_inconsistent"] == 1

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "FAILED"


def test_admin_lease_recovery_scan_retries_and_stales_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5, against the real
    migrated schema — confirms attempt/max_attempts round-trip through the real
    agent_tasks table and drive the retry-vs-stale split correctly.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-lease-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/resume", json={"idempotency_key": "resume-lease-pg-1"})
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]

    container = get_container()
    claimed_record = container.agent_task_repository.find_by_id(AgentTaskId(uuid.UUID(agent_task_id)))
    container.agent_task_repository.save(dataclasses.replace(
        claimed_record, lease_expires_at=claimed_record.updated_at - timedelta(seconds=1),
        task_version=claimed_record.task_version + 1, max_attempts=3,
    ))

    scanned = client.post("/internal/agent-runtime/v1/admin/agent-tasks/lease-recovery-scan", headers={"X-Actor-Id": "ops-user-1"})
    assert scanned.status_code == 200
    body = scanned.json()
    assert body["scanned"] == 1
    assert body["retried"] == 1
    assert body["staled"] == 0

    retried = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert retried.json()["state"] == "READY"

    # Reclaim and drive this attempt to the (now-2-of-3) budget's exhaustion.
    reclaimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-2", "lease_seconds": 60,
    })
    assert reclaimed.json()["state"] == "CLAIMED"
    reclaimed_record = container.agent_task_repository.find_by_id(AgentTaskId(uuid.UUID(agent_task_id)))
    container.agent_task_repository.save(dataclasses.replace(
        reclaimed_record, lease_expires_at=reclaimed_record.updated_at - timedelta(seconds=1),
        task_version=reclaimed_record.task_version + 1, attempt=reclaimed_record.max_attempts,
    ))

    rescanned = client.post("/internal/agent-runtime/v1/admin/agent-tasks/lease-recovery-scan", headers={"X-Actor-Id": "ops-user-1"})
    assert rescanned.status_code == 200
    rebody = rescanned.json()
    assert rebody["scanned"] == 1
    assert rebody["retried"] == 0
    assert rebody["staled"] == 1

    staled = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert staled.json()["state"] == "STALE"


def test_admin_replay_dead_letter_requeues_and_republishes_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-030 10-failure-handling §"Runtime 崩溃后怎么恢复" step 3: "重放未发布 outbox"
    against the real migrated schema — confirms find_dead_letter/requeue round-trip
    through the real outbox_events table.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-outbox-replay-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    container = get_container()
    now = container.clock.now()
    dead_lettered = OutboxRecord(
        outbox_id=uuid.uuid4(), workflow_instance_id=WorkflowInstanceId(uuid.UUID(workflow_instance_id)),
        ticket_id=TicketId(uuid.uuid4()), correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(),
        event_type="workflow.started.v1", schema_version=1, payload="{}", occurred_at=now,
        status=OutboxStatus.DEAD_LETTER, attempts=5,
    )
    container.outbox_repository.append(dead_lettered)

    replayed = client.post("/internal/agent-runtime/v1/admin/outbox/replay-dead-letter", headers={"X-Actor-Id": "ops-user-1"})
    assert replayed.status_code == 200
    body = replayed.json()
    assert body["scanned"] >= 1
    assert body["published"] >= 1
    assert body["dead_lettered"] == 0
    assert container.outbox_repository.find_dead_letter(100) == []


def test_admin_force_recover_workflow_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "force recover workflow"."""
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-force-recover-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    container = get_container()
    running = container.workflow_instance_repository.find_by_id(WorkflowInstanceId(uuid.UUID(workflow_instance_id)))
    container.workflow_instance_repository.save(
        dataclasses.replace(running, state=WorkflowState.PAUSED, pause_generation=1, workflow_version=running.workflow_version + 1)
    )

    forced = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/force-recover", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert forced.status_code == 200
    assert forced.json()["checkpoint_inconsistent"] == 1

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "FAILED"


def test_admin_retry_agent_task_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "retry failed task"."""
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-admin-retry-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 300,
    })
    agent_task_id = claimed.json()["agent_task_id"]

    container = get_container()
    claimed_record = container.agent_task_repository.find_by_id(AgentTaskId(uuid.UUID(agent_task_id)))
    container.agent_task_repository.save(dataclasses.replace(claimed_record, max_attempts=3, task_version=claimed_record.task_version + 1))

    retried = client.post(
        f"/internal/agent-runtime/v1/admin/agent-tasks/{agent_task_id}/retry", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert retried.status_code == 200
    assert retried.json()["state"] == "READY"

    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "READY"


def test_admin_quarantine_poison_event_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined"."""
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-quarantine-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-quarantine-pg-1", "claim_token": claim_token,
    })
    event_body = {
        "event_id": "evt-quarantine-pg-1", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z", "payload": '{"status": "COMPLETED"',
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 422

    poison_events = client.get("/internal/agent-runtime/v1/admin/poison-events", headers={"X-Actor-Id": "ops-user-1"})
    [entry] = [e for e in poison_events.json()["poison_events"] if e["event_id"] == "evt-quarantine-pg-1"]
    assert entry["quarantined_at"] is None

    quarantined = client.post(
        f"/internal/agent-runtime/v1/admin/poison-events/{entry['id']}/quarantine", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert quarantined.status_code == 200
    assert quarantined.json()["quarantined_at"] is not None


def test_checkpoint_and_poison_event_payloads_are_redacted_in_responses_but_not_at_rest(client: TestClient) -> None:
    """SPEC-ARO-033 11-security §"Data Protection": redaction is applied only in the
    view-construction layer — the persisted CheckpointRecord/PoisonEventRecord.payload
    must stay intact in the real database (a future poison-event replay needs the real
    payload, not a redacted one) even though the HTTP response is redacted.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-redact-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart","token":"super-secret-value"}',
        "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-redact-pg-1", "claim_token": claim_token,
    })

    checkpoint = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert "super-secret-value" not in checkpoint.json()["payload"]
    assert "***REDACTED***" in checkpoint.json()["payload"]

    container = get_container()
    [stored_checkpoint] = [
        c for c in container.checkpoint_repository.find_by_workflow_instance_id(WorkflowInstanceId(uuid.UUID(workflow_instance_id)))
        if c.type.name == "PRE_TOOL_CALL"
    ]
    assert "super-secret-value" in stored_checkpoint.payload
    assert "***REDACTED***" not in stored_checkpoint.payload

    event_body = {
        "event_id": "evt-redact-pg-1", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z", "payload": '{"password": "hunter2", "status": "COMPLETED"',
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 422

    poison_events = client.get("/internal/agent-runtime/v1/admin/poison-events", headers={"X-Actor-Id": "ops-user-1"})
    [entry] = [e for e in poison_events.json()["poison_events"] if e["event_id"] == "evt-redact-pg-1"]
    assert "hunter2" not in entry["payload"]
    assert "***REDACTED***" in entry["payload"]

    [stored_poison_event] = [
        p for p in container.poison_event_repository.find_all(100) if p.event_id == "evt-redact-pg-1"
    ]
    assert "hunter2" in stored_poison_event.payload
    assert "***REDACTED***" not in stored_poison_event.payload


def test_admin_audit_events_records_a_workflow_transition_against_real_postgres(client: TestClient) -> None:
    """SPEC-ARO-034 12-observability §"Audit Events": against the real migrated schema —
    confirms the audit_events table (migration f1a2b3c4d5e6) is genuinely load-bearing,
    not just the in-memory adapter.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-audit-pg-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    audit_events = client.get("/internal/agent-runtime/v1/admin/audit-events", headers={"X-Actor-Id": "ops-user-1"})
    assert audit_events.status_code == 200
    matches = [
        e for e in audit_events.json()["audit_events"]
        if e["workflow_instance_id"] == workflow_instance_id and e["action"] == "start_workflow"
    ]
    assert len(matches) == 1
    assert matches[0]["audit_type"] == "WORKFLOW_TRANSITION"
    assert matches[0]["outcome"] == "SUCCESS"
