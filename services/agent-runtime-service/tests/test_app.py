"""SPEC-ARO-001 acceptance-criteria: the module boundary must be delivered as
running, bootable code — this drives the full FastAPI app through the same
lifecycle a real Ticket-driven Workflow would go through: start -> pause -> resume
-> claim -> request-tool -> complete -> admin recover. Stays on the SPEC-ARO-001
in-memory adapters (fast, hermetic, no Docker) even though SPEC-ARO-002 makes
"postgres" the container's real-run default — see
tests/integration/test_app_postgres_integration.py for the same walk against
real Postgres.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from agentruntime.container import get_container
from agentruntime.main import create_app
from agentruntime.settings import Settings

pytestmark = pytest.mark.unit


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: Settings(agent_runtime_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


def _start_workflow(client: TestClient, idempotency_key: str) -> str:
    response = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()),
        "ticket_cycle_id": str(uuid.uuid4()),
        "workflow_definition_id": "triage-v1",
        "definition_version": 1,
        "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": idempotency_key,
    })
    assert response.status_code == 201
    return response.json()["workflow_instance_id"]


def test_health(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_full_workflow_lifecycle(client: TestClient) -> None:
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
    assert started.json()["state"] == "RUNNING"

    # A genuinely new idempotency key against the same ticket/cycle still hits the
    # "one active instance" domain guard, not the idempotency cache.
    duplicate = client.post("/internal/agent-runtime/v1/workflows", json={**start_body, "idempotency_key": "start-2"})
    assert duplicate.status_code == 409

    paused = client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/pause", json={"idempotency_key": "pause-1"})
    assert paused.status_code == 200
    assert paused.json()["state"] == "PAUSED"

    duplicate_pause = client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/pause", json={"idempotency_key": "pause-1"})
    assert duplicate_pause.status_code == 200

    resumed = client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/resume", json={"idempotency_key": "resume-1"})
    assert resumed.status_code == 200
    assert resumed.json()["state"] == "RUNNING"

    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert claimed.status_code == 200
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    workflow_version = claimed.json()["workflow_version"]
    assert claimed.json()["state"] == "CLAIMED"
    assert claim_token is not None
    assert workflow_version is not None

    tool_response = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-1",
    })
    assert tool_response.status_code == 200
    assert tool_response.json()["status"] == "DISPATCHED"

    wrong_claim_token = client.post(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}/complete", json={
        "claim_token": str(uuid.uuid4()), "idempotency_key": "complete-x", "workflow_version": workflow_version,
        "result_payload": "should not apply",
    })
    assert wrong_claim_token.status_code == 409

    stale_workflow_version = client.post(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "complete-stale", "workflow_version": workflow_version - 1,
        "result_payload": "should not apply",
    })
    assert stale_workflow_version.status_code == 409
    assert stale_workflow_version.json()["error"]["code"] == "STALE_WORKFLOW_VERSION"

    completed = client.post(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "complete-1", "workflow_version": workflow_version,
        "result_payload": "diagnostics collected",
    })
    assert completed.status_code == 200
    assert completed.json()["state"] == "COMPLETED"

    # SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6: "collect"
    # was this workflow's only task, so completing it also settles the whole graph — the
    # Workflow Instance itself auto-transitions to COMPLETED, no admin action needed.
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
    dispatch_body = dispatched.json()
    assert dispatch_body["scanned"] >= 1
    assert dispatch_body["published"] == dispatch_body["scanned"]
    assert dispatch_body["failed"] == 0
    assert dispatch_body["dead_lettered"] == 0

    # SPEC-ARO-004's admin complete endpoint still exists for a workflow that genuinely
    # needs it, but this one is already auto-completed — a new key against an
    # already-terminal instance is a real conflict, not a no-op.
    admin_complete_after_auto_complete = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/complete",
        json={"idempotency_key": "complete-workflow-1"}, headers={"X-Actor-Id": "ops-user-1"},
    )
    assert admin_complete_after_auto_complete.status_code == 409


def test_admin_complete_workflow_is_idempotent_and_rejects_a_new_key_once_terminal(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-complete-1")

    first = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/complete",
        json={"idempotency_key": "complete-1"}, headers={"X-Actor-Id": "ops-user-1"},
    )
    assert first.status_code == 200
    assert first.json()["state"] == "COMPLETED"

    replay = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/complete",
        json={"idempotency_key": "complete-1"}, headers={"X-Actor-Id": "ops-user-1"},
    )
    assert replay.status_code == 200
    assert replay.json()["state"] == "COMPLETED"

    conflict = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/complete",
        json={"idempotency_key": "complete-2"}, headers={"X-Actor-Id": "ops-user-1"},
    )
    assert conflict.status_code == 409


def test_admin_fail_workflow_records_the_failure_reason(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-fail-1")

    response = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/fail",
        json={"idempotency_key": "fail-1", "failure_reason": "tool exhausted retries"},
        headers={"X-Actor-Id": "ops-user-1"},
    )

    assert response.status_code == 200
    assert response.json()["state"] == "FAILED"


def test_admin_fail_workflow_rejects_a_blank_reason(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-fail-2")

    response = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/fail",
        json={"idempotency_key": "fail-x", "failure_reason": "   "}, headers={"X-Actor-Id": "ops-user-1"},
    )

    assert response.status_code == 400


def test_admin_cancel_workflow_records_the_cancellation_reason(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-cancel-1")

    response = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/cancel",
        json={"idempotency_key": "cancel-1", "reason": "ticket cancelled upstream"},
        headers={"X-Actor-Id": "ops-user-1"},
    )

    assert response.status_code == 200
    assert response.json()["state"] == "CANCELLED"


def test_admin_workflow_lifecycle_endpoints_404_for_an_unknown_workflow_instance(client: TestClient) -> None:
    unknown_id = str(uuid.uuid4())

    complete_response = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{unknown_id}/complete", json={"idempotency_key": "complete-x"},
    )
    fail_response = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{unknown_id}/fail",
        json={"idempotency_key": "fail-x", "failure_reason": "unreachable"},
    )
    cancel_response = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{unknown_id}/cancel",
        json={"idempotency_key": "cancel-x", "reason": "unreachable"},
    )

    assert complete_response.status_code == 404
    assert fail_response.status_code == 404
    assert cancel_response.status_code == 404


def _ticket_created_body(event_id: str, ticket_id: str | None = None, ticket_cycle_id: str | None = None) -> dict:
    return {
        "event_id": event_id, "event_type": "ticket.created.v1", "producer": "ticket-workflow-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()),
        "ticket_id": ticket_id or str(uuid.uuid4()), "ticket_cycle_id": ticket_cycle_id or str(uuid.uuid4()),
        "priority": "HIGH", "category": "network_outage", "created_by": "ticket-workflow-service",
        "occurred_at": "2026-01-01T00:00:00Z",
    }


def test_ticket_created_starts_a_workflow_instance(client: TestClient) -> None:
    """SPEC-ARO-005 04-use-cases UC-01: ticket.created -> Workflow Instance RUNNING with
    its initial task graph materialized, all without the caller ever supplying a
    WorkflowDefinition (unlike the direct REST /workflows command).
    """
    response = client.post("/internal/agent-runtime/v1/events/ticket-created", json=_ticket_created_body("evt-1"))

    assert response.status_code == 200
    assert response.json() == {"eventId": "evt-1", "applied": True}


def test_ticket_created_is_deduplicated_by_event_id(client: TestClient) -> None:
    body = _ticket_created_body("evt-dup")
    client.post("/internal/agent-runtime/v1/events/ticket-created", json=body)

    replay = client.post("/internal/agent-runtime/v1/events/ticket-created", json=body)

    assert replay.status_code == 200
    assert replay.json()["applied"] is False


def test_a_second_ticket_created_for_the_same_ticket_cycle_does_not_start_a_second_instance(client: TestClient) -> None:
    ticket_id = str(uuid.uuid4())
    ticket_cycle_id = str(uuid.uuid4())
    first = client.post(
        "/internal/agent-runtime/v1/events/ticket-created", json=_ticket_created_body("evt-1", ticket_id, ticket_cycle_id)
    )
    assert first.json()["applied"] is True

    # A different eventId (not literal redelivery) for the same ticket cycle: still
    # collapses onto the same Start command via the ticketId+ticketCycleId+workflowType
    # idempotency key, so this is reported as "applied" (the event was evaluated) even
    # though no second Workflow Instance was created.
    second = client.post(
        "/internal/agent-runtime/v1/events/ticket-created", json=_ticket_created_body("evt-2", ticket_id, ticket_cycle_id)
    )
    assert second.status_code == 200
    assert second.json()["applied"] is True


def test_get_workflow_instance_by_id(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-query-1")

    response = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")

    assert response.status_code == 200
    assert response.json()["workflow_instance_id"] == workflow_instance_id
    assert response.json()["state"] == "RUNNING"


def test_get_workflow_instance_by_id_404_for_unknown_id(client: TestClient) -> None:
    response = client.get(f"/internal/agent-runtime/v1/workflows/{uuid.uuid4()}")

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "WORKFLOW_INSTANCE_NOT_FOUND"


def test_get_workflow_instances_by_ticket(client: TestClient) -> None:
    ticket_id = str(uuid.uuid4())
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": ticket_id, "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-by-ticket-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    response = client.get(f"/internal/agent-runtime/v1/workflows/by-ticket/{ticket_id}")

    assert response.status_code == 200
    assert [w["workflow_instance_id"] for w in response.json()] == [workflow_instance_id]


def test_get_workflow_instances_by_ticket_returns_empty_list_for_unknown_ticket(client: TestClient) -> None:
    response = client.get(f"/internal/agent-runtime/v1/workflows/by-ticket/{uuid.uuid4()}")

    assert response.status_code == 200
    assert response.json() == []


def test_get_latest_checkpoint(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-checkpoint-1")

    response = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")

    assert response.status_code == 200
    body = response.json()
    assert body["workflow_instance_id"] == workflow_instance_id
    assert body["type"] == "STARTED"


def test_get_latest_checkpoint_404_for_unknown_workflow_instance(client: TestClient) -> None:
    response = client.get(f"/internal/agent-runtime/v1/workflows/{uuid.uuid4()}/checkpoints/latest")

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "WORKFLOW_INSTANCE_NOT_FOUND"


def test_get_agent_task_by_id(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-task-query-1")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]

    response = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")

    assert response.status_code == 200
    assert response.json()["agent_task_id"] == agent_task_id
    assert response.json()["state"] == "CLAIMED"


def test_get_agent_task_by_id_404_for_unknown_id(client: TestClient) -> None:
    response = client.get(f"/internal/agent-runtime/v1/agent-tasks/{uuid.uuid4()}")

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "AGENT_TASK_NOT_FOUND"


def test_completing_a_task_unlocks_its_downstream_dependent_through_the_real_command_flow(client: TestClient) -> None:
    """SPEC-ARO-008 04-use-cases UC-02 steps 5-6: before this spec, "remediate" could
    never be claimed through the real command flow at all — CompleteAgentTaskService
    never re-invoked the Coordinator, so a two-task graph with a dependency was
    permanently stuck after its first task finished.
    """
    response = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [
            {"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"},
            {"task_key": "remediate", "task_type": "apply_fix", "depends_on": ["collect"], "join_policy": "ALL_SUCCESS"},
        ],
        "idempotency_key": "start-unlock-1",
    })
    workflow_instance_id = response.json()["workflow_instance_id"]

    # "remediate" does not exist yet — its dependency hasn't completed.
    remediate_before = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "remediate", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert remediate_before.status_code == 404

    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert claimed.status_code == 200
    collect_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    workflow_version = claimed.json()["workflow_version"]

    completed = client.post(f"/internal/agent-runtime/v1/agent-tasks/{collect_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "complete-collect-1", "workflow_version": workflow_version,
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


def test_claim_ready_batch_endpoint_claims_by_agent_role_through_the_real_command_flow(client: TestClient) -> None:
    """SPEC-ARO-009 05-api-contracts "Claim Task": "Worker provides agentRole, workerId,
    and maxTasks. Service returns tasks with leases."
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [
            {"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS", "agent_role": "triage_agent"},
        ],
        "idempotency_key": "start-claim-ready-1",
    })
    assert started.status_code == 201
    workflow_instance_id = started.json()["workflow_instance_id"]

    wrong_role = client.post("/internal/agent-runtime/v1/agent-tasks/claim-ready", json={
        "agent_role": "kb_agent", "worker_id": "worker-1", "max_tasks": 5, "lease_seconds": 60,
    })
    assert wrong_role.status_code == 200
    assert wrong_role.json()["tasks"] == []

    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim-ready", json={
        "agent_role": "triage_agent", "worker_id": "worker-1", "max_tasks": 5, "lease_seconds": 60,
    })
    assert claimed.status_code == 200
    tasks = claimed.json()["tasks"]
    assert len(tasks) == 1
    assert tasks[0]["workflow_instance_id"] == workflow_instance_id
    assert tasks[0]["task_key"] == "collect"
    assert tasks[0]["state"] == "CLAIMED"
    assert tasks[0]["agent_role"] == "triage_agent"
    assert tasks[0]["claim_token"] is not None
    assert tasks[0]["workflow_version"] is not None

    completed = client.post(f"/internal/agent-runtime/v1/agent-tasks/{tasks[0]['agent_task_id']}/complete", json={
        "claim_token": tasks[0]["claim_token"], "idempotency_key": "complete-claim-ready-1",
        "workflow_version": tasks[0]["workflow_version"], "result_payload": "ok",
    })
    assert completed.status_code == 200
    assert completed.json()["state"] == "COMPLETED"


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


def test_a_multi_task_workflow_auto_completes_only_once_every_task_succeeds_through_the_real_command_flow(client: TestClient) -> None:
    """SPEC-ARO-010 08-transaction-and-outbox §"Task Complete Transaction" step 6, through
    the real HTTP command flow rather than calling the service directly.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [
            {"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"},
            {"task_key": "remediate", "task_type": "apply_fix", "depends_on": ["collect"], "join_policy": "ALL_SUCCESS"},
        ],
        "idempotency_key": "start-multi-complete-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    collect_completed = _claim_and_complete(
        client, workflow_instance_id, "collect", "worker-1", "complete-collect-1", result_payload="diagnostics collected"
    )
    assert collect_completed.status_code == 200

    # only the first of two tasks finished — must not auto-complete prematurely.
    mid_flight = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert mid_flight.json()["state"] == "RUNNING"

    remediate_completed = _claim_and_complete(
        client, workflow_instance_id, "remediate", "worker-2", "complete-remediate-1", result_payload="fix applied"
    )
    assert remediate_completed.status_code == 200

    settled = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert settled.json()["state"] == "COMPLETED"


def test_a_multi_task_workflow_auto_fails_when_a_task_fails_through_the_real_command_flow(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [
            {"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"},
            {"task_key": "remediate", "task_type": "apply_fix", "depends_on": ["collect"], "join_policy": "ALL_SUCCESS"},
        ],
        "idempotency_key": "start-multi-fail-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    collect_failed = _claim_and_complete(
        client, workflow_instance_id, "collect", "worker-1", "complete-collect-1", failure_reason="diagnostics tool crashed"
    )
    assert collect_failed.status_code == 200
    assert collect_failed.json()["state"] == "FAILED_FINAL"

    settled = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert settled.json()["state"] == "FAILED"

    # "remediate" was never materialized (its only dependency permanently failed) and the
    # workflow is now terminal — ClaimAgentTaskService rejects on the workflow's own
    # RUNNING check before it would even look for the task, so this is a 409, not a 404.
    remediate_claim = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "remediate", "worker_id": "worker-2", "lease_seconds": 60,
    })
    assert remediate_claim.status_code == 409
