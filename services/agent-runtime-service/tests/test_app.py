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

    # SPEC-ARO-012 08-transaction-and-outbox §"Pause Transaction" step 6: "Write PAUSED
    # checkpoint."
    pause_checkpoint = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert pause_checkpoint.json()["type"] == "PAUSE_POINT"
    assert pause_checkpoint.json()["workflow_version"] == paused.json()["workflow_version"]

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

    # request-tool's own WAITING_TOOL/WAITING_FOR_TOOL side effects are covered by
    # test_requesting_a_tool_moves_the_task_and_workflow_into_a_waiting_state below, kept
    # deliberately separate from this test: entering that wait is a one-way trip until
    # SPEC-ARO-020 exists (nothing wakes it back up yet), so exercising it here would
    # derail every assertion below that expects "collect" to still be a normally
    # completable, RUNNING-workflow task.
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

    # SPEC-ARO-016: the rejected stale-generation submission also marks the task STALE —
    # the original claim_token can no longer complete it (its own claim is no longer
    # trusted), so a fresh claim is required before the task can be completed.
    stale_claim_attempt = client.post(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "complete-after-stale", "workflow_version": workflow_version,
        "result_payload": "should not apply either",
    })
    assert stale_claim_attempt.status_code == 409
    assert stale_claim_attempt.json()["error"]["code"] == "INVALID_STATE_TRANSITION"

    reclaimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-2", "lease_seconds": 60,
    })
    assert reclaimed.status_code == 200
    assert reclaimed.json()["state"] == "CLAIMED"
    claim_token = reclaimed.json()["claim_token"]
    workflow_version = reclaimed.json()["workflow_version"]

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
    # SPEC-ARO-005 writes a STARTED checkpoint on start, SPEC-ARO-012 writes a
    # PAUSE_POINT checkpoint on pause, and SPEC-ARO-008 writes an AFTER_TASK checkpoint
    # on completion.
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


def test_requesting_a_tool_moves_the_task_and_workflow_into_a_waiting_state(client: TestClient) -> None:
    """SPEC-ARO-018 11-security §"Authorization": request-tool requires proof of holding
    the task's own claim. SPEC-ARO-019 08-transaction-and-outbox §"Tool Request
    Transaction" steps 4-6: the task moves to WAITING_TOOL, the workflow to
    WAITING_FOR_TOOL, and the Tool Request is only PENDING until
    DispatchToolRequestsService (not ToolGatewayPort synchronously) actually dispatches
    it. Once WAITING_TOOL, the original claim can no longer complete the task directly —
    that is by design (02-business-invariants: "Tool result 必须通过 tool.completed 或
    tool.failed 回到 Runtime").
    """
    workflow_instance_id = _start_workflow(client, "start-tool-1")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    wrong_claim_token = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-wrong-claim", "claim_token": str(uuid.uuid4()),
    })
    assert wrong_claim_token.status_code == 409
    assert wrong_claim_token.json()["error"]["code"] == "CLAIM_TOKEN_MISMATCH"

    tool_response = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-1", "claim_token": claim_token,
    })
    assert tool_response.status_code == 200
    # SPEC-ARO-019: PENDING, not DISPATCHED — the Tool Gateway call happens later, outside
    # this transaction.
    assert tool_response.json()["status"] == "PENDING"

    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "WAITING_TOOL"

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "WAITING_FOR_TOOL"


def test_requesting_a_tool_with_an_unauthorized_capability_is_rejected(client: TestClient) -> None:
    """SPEC-ARO-032 11-security §"Tool Gateway 强制路径": StaticCapabilityPolicyAdapter's
    default policy does not authorize kb_agent for service_operations.
    """
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{
            "task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS",
            "agent_role": "kb_agent",
        }],
        "idempotency_key": "start-capability-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert claimed.status_code == 200
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    rejected = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-capability-1", "claim_token": claim_token, "capability": "service_operations",
    })
    assert rejected.status_code == 403
    assert rejected.json()["error"]["code"] == "CAPABILITY_NOT_AUTHORIZED"

    # Rejected before any side effect — the task is still just CLAIMED.
    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "CLAIMED"


def test_requesting_a_tool_with_an_authorized_capability_succeeds(client: TestClient) -> None:
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()), "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{
            "task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS",
            "agent_role": "triage_agent",
        }],
        "idempotency_key": "start-capability-2",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    accepted = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-capability-2", "claim_token": claim_token, "capability": "service_operations",
    })
    assert accepted.status_code == 200
    assert accepted.json()["status"] == "PENDING"


def test_a_pending_tool_request_blocks_direct_completion_and_is_later_dispatched(client: TestClient) -> None:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction": once a task
    is WAITING_TOOL, the original claim can no longer complete it directly (02-business-
    invariants: "Tool result 必须通过 tool.completed 或 tool.failed 回到 Runtime"), and the
    Tool Request itself is only PENDING until DispatchToolRequestsService — not
    ToolGatewayPort synchronously inside request-tool — actually dispatches it.
    """
    workflow_instance_id = _start_workflow(client, "start-tool-3")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-3", "claim_token": claim_token,
    })

    # The task is no longer actively claimed in the sense complete() requires.
    blocked_complete = client.post(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "complete-while-waiting", "workflow_version": claimed.json()["workflow_version"],
        "result_payload": "should not apply",
    })
    assert blocked_complete.status_code == 409

    dispatched = client.post("/internal/agent-runtime/v1/admin/tool-requests/dispatch", headers={"X-Actor-Id": "ops-user-1"})
    assert dispatched.status_code == 200
    dispatch_body = dispatched.json()
    assert dispatch_body["scanned"] == 1
    assert dispatch_body["dispatched"] == 1


def test_a_tool_completed_v1_event_wakes_the_workflow_and_completes_the_task(client: TestClient) -> None:
    """SPEC-ARO-020 04-use-cases UC-04 "消费 tool.completed": the counterpart to the
    previous test — once a real tool.completed.v1 event references the pending Tool
    Request, the task moves WAITING_TOOL -> COMPLETED, the workflow wakes
    WAITING_FOR_TOOL -> RUNNING and then auto-settles to COMPLETED ("collect" was this
    workflow's only task), and a duplicate delivery of the same event is idempotent.
    """
    workflow_instance_id = _start_workflow(client, "start-tool-2")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    tool_response = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-2", "claim_token": claim_token,
    })
    tool_request_id = tool_response.json()["tool_request_id"]

    workflow_waiting = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    waiting_workflow_version = workflow_waiting.json()["workflow_version"]

    dispatched = client.post("/internal/agent-runtime/v1/admin/tool-requests/dispatch", headers={"X-Actor-Id": "ops-user-1"})
    assert dispatched.status_code == 200

    event_body = {
        "event_id": "evt-tool-1", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
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

    # "collect" was this workflow's only task, so waking it back to RUNNING immediately
    # settles the whole graph to COMPLETED — mirroring SPEC-ARO-010's own auto-settle path.
    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "COMPLETED"

    checkpoints = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert checkpoints.json()["type"] == "AFTER_TASK"
    checkpoint_payload = json.loads(checkpoints.json()["payload"])
    assert checkpoint_payload["resultPayload"] == "diagnostics ok"

    # A duplicate delivery under the same event_id is a dedup no-op — mark_processed's
    # own (event_id, consumer_name) key rejects re-applying an outcome that already landed.
    duplicate = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert duplicate.status_code == 200
    assert duplicate.json()["applied"] is False


def test_a_failed_tool_completed_v1_event_fails_the_task(client: TestClient) -> None:
    """SPEC-ARO-020 06-event-contracts §"tool.completed.v1": the same event type's
    `status` field, not a separate tool.failed.v1 contract, carries the FAILED outcome.
    """
    workflow_instance_id = _start_workflow(client, "start-tool-3")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    tool_response = client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-3", "claim_token": claim_token,
    })
    tool_request_id = tool_response.json()["tool_request_id"]

    workflow_waiting = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    waiting_workflow_version = workflow_waiting.json()["workflow_version"]

    event_body = {
        "event_id": "evt-tool-2", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
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

    # "collect" was this workflow's only task and it failed outright, so the workflow
    # graph is settled with no successful outcome — the same auto-fail path
    # CompleteAgentTaskService's own failure settlement exercises.
    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "FAILED"

    checkpoints = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    checkpoint_payload = json.loads(checkpoints.json()["payload"])
    assert checkpoint_payload["failureReason"] == "tool exhausted retries"


def _seed_waiting_for_approval(client: TestClient, workflow_instance_id: str) -> None:
    """SPEC-ARO-021: unlike WAITING_FOR_TOOL (SPEC-ARO-019 built a real request-tool HTTP
    entry point), nothing in this codebase's roadmap yet builds an entry point into
    WAITING_FOR_APPROVAL (AgentTaskState's own WAITING_EXTERNAL docstring: "once an
    approval/verification/input wait exists" — no spec has built that entry path yet).
    The wait is therefore seeded directly through the repository, respecting the same
    one-version-at-a-time CAS every other test in this session seeds a non-1 version
    through, so only the consumption side (POST /events) needs to go through real HTTP.
    """
    container = get_container()
    running = container.workflow_instance_repository.find_by_id(WorkflowInstanceId(uuid.UUID(workflow_instance_id)))
    container.workflow_instance_repository.save(
        dataclasses.replace(running, state=WorkflowState.WAITING_FOR_APPROVAL, workflow_version=running.workflow_version + 1)
    )


def test_an_approval_granted_v1_event_wakes_the_workflow_from_waiting_for_approval(client: TestClient) -> None:
    """SPEC-ARO-021 04-use-cases UC-03 "消费 approval.granted": decision == "APPROVED"
    wakes WAITING_FOR_APPROVAL back to RUNNING and records a RECOVERY_SNAPSHOT
    checkpoint; a duplicate delivery is idempotent (workflow no longer WAITING_FOR_APPROVAL).
    """
    workflow_instance_id = _start_workflow(client, "start-approval-1")
    _seed_waiting_for_approval(client, workflow_instance_id)

    event_body = {
        "event_id": "evt-approval-1", "event_type": "approval.granted.v1", "producer": "approval-service", "schema_version": 1,
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

    # Duplicate delivery under the same event_id is a dedup no-op at the generic gate.
    duplicate = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert duplicate.status_code == 200
    assert duplicate.json()["applied"] is False


def test_an_approval_granted_v1_event_with_a_non_approved_decision_fails_the_workflow(client: TestClient) -> None:
    """The recommended resolution from this spec's own decision discriminator: any
    decision other than "APPROVED" fails the workflow outright via the existing
    FailWorkflowService, with the decision/approvedBy folded into the auditable reason.
    """
    workflow_instance_id = _start_workflow(client, "start-approval-2")
    _seed_waiting_for_approval(client, workflow_instance_id)

    event_body = {
        "event_id": "evt-approval-2", "event_type": "approval.granted.v1", "producer": "approval-service", "schema_version": 1,
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
    """SPEC-ARO-022: mirrors _seed_waiting_for_approval — no spec builds an entry path
    into WAITING_FOR_VERIFICATION either.
    """
    container = get_container()
    running = container.workflow_instance_repository.find_by_id(WorkflowInstanceId(uuid.UUID(workflow_instance_id)))
    container.workflow_instance_repository.save(
        dataclasses.replace(running, state=WorkflowState.WAITING_FOR_VERIFICATION, workflow_version=running.workflow_version + 1)
    )


def test_a_verification_completed_v1_event_wakes_the_workflow_to_running(client: TestClient) -> None:
    """SPEC-ARO-022 04-use-cases UC-05 "消费 verification.completed": passed == True
    wakes WAITING_FOR_VERIFICATION back to RUNNING — "collect" is still outstanding here,
    so the task graph is not yet settled and the workflow stays RUNNING rather than
    auto-completing (that path is covered at the application-service test level).
    """
    workflow_instance_id = _start_workflow(client, "start-verification-1")
    _seed_waiting_for_verification(client, workflow_instance_id)

    event_body = {
        "event_id": "evt-verification-1", "event_type": "verification.completed.v1", "producer": "verification-service", "schema_version": 1,
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

    # Duplicate delivery under the same event_id is a dedup no-op at the generic gate.
    duplicate = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert duplicate.status_code == 200
    assert duplicate.json()["applied"] is False


def test_a_verification_completed_v1_event_with_passed_false_fails_the_workflow(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-verification-2")
    _seed_waiting_for_verification(client, workflow_instance_id)

    event_body = {
        "event_id": "evt-verification-2", "event_type": "verification.completed.v1", "producer": "verification-service", "schema_version": 1,
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


def test_a_ticket_cancelled_v1_event_cancels_the_active_workflow(client: TestClient) -> None:
    """SPEC-ARO-023 06-event-contracts (02-ticket-workflow PUB-014 "ticket.cancelled.v1"):
    reuses the existing (previously admin-only) CancelWorkflowService — its own docstring
    already named this spec as the trigger owner.
    """
    ticket_id, ticket_cycle_id = str(uuid.uuid4()), str(uuid.uuid4())
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-cycle-cancel-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    event_body = {
        "event_id": "evt-cycle-cancel-1", "event_type": "ticket.cancelled.v1", "producer": "ticket-workflow-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id,
        "cancel_reason_code": "NO_LONGER_NEEDED", "occurred_at": "2026-01-01T00:00:00Z",
    }
    ingested = client.post("/internal/agent-runtime/v1/events/ticket-cancelled", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "CANCELLED"

    # Duplicate delivery under the same event_id is a dedup no-op.
    duplicate = client.post("/internal/agent-runtime/v1/events/ticket-cancelled", json=event_body)
    assert duplicate.status_code == 200
    assert duplicate.json()["applied"] is False


def test_a_ticket_reopened_v1_event_cancels_the_previous_cycles_workflow(client: TestClient) -> None:
    """SPEC-ARO-023 06-event-contracts (02-ticket-workflow PUB-015 "ticket.reopened.v1"):
    the new cycle's own Workflow Instance is started separately by a future
    ticket.created.v1 for newTicketCycleId — this event only ends the old cycle's automation.
    """
    ticket_id, previous_cycle_id, new_cycle_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": ticket_id, "ticket_cycle_id": previous_cycle_id, "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-cycle-reopen-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    event_body = {
        "event_id": "evt-cycle-reopen-1", "event_type": "ticket.reopened.v1", "producer": "ticket-workflow-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": ticket_id,
        "previous_ticket_cycle_id": previous_cycle_id, "new_ticket_cycle_id": new_cycle_id, "reason_code": "ISSUE_RECURRED",
        "occurred_at": "2026-01-01T00:00:00Z",
    }
    ingested = client.post("/internal/agent-runtime/v1/events/ticket-reopened", json=event_body)
    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "CANCELLED"


def test_ticket_workflow_cancelled_outbox_envelope_cancels_the_active_workflow(client: TestClient) -> None:
    ticket_id, ticket_cycle_id = str(uuid.uuid4()), str(uuid.uuid4())
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": ticket_id, "ticket_cycle_id": ticket_cycle_id, "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-cycle-cancel-outbox-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    event_body = {
        "eventId": "evt-cycle-cancel-outbox-1", "eventType": "ticket.cancelled", "eventVersion": "1.0",
        "routingKey": "ticket.cancelled.v1", "aggregateType": "Ticket", "aggregateId": ticket_id,
        "aggregateVersion": 2, "ticketId": ticket_id, "traceId": "trace-cancel-1",
        "correlationId": str(uuid.uuid4()), "causationId": str(uuid.uuid4()), "dataClassification": "INTERNAL",
        "occurredAt": "2026-01-01T00:00:00Z",
        "payload": {
            "supportQueueId": str(uuid.uuid4()), "assigneeId": None, "resolutionCycleId": ticket_cycle_id,
            "previousStatus": "IN_PROGRESS", "newStatus": "CANCELLED", "cancelReasonCode": "NO_LONGER_NEEDED",
            "cancelReason": "Requester no longer needs support", "cancelledBy": "requester-1",
            "cancelledAt": "2026-01-01T00:00:00Z",
        },
    }
    ingested = client.post("/internal/agent-runtime/v1/events/ticket-cancelled", json=event_body)

    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True
    assert client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}").json()["state"] == "CANCELLED"


def test_ticket_workflow_reopened_outbox_envelope_cancels_the_previous_cycles_workflow(client: TestClient) -> None:
    ticket_id, previous_cycle_id, new_cycle_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    started = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": ticket_id, "ticket_cycle_id": previous_cycle_id, "workflow_definition_id": "triage-v1",
        "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": "start-cycle-reopen-outbox-1",
    })
    workflow_instance_id = started.json()["workflow_instance_id"]

    event_body = {
        "eventId": "evt-cycle-reopen-outbox-1", "eventType": "ticket.reopened", "eventVersion": "1.0",
        "routingKey": "ticket.reopened.v1", "aggregateType": "Ticket", "aggregateId": ticket_id,
        "aggregateVersion": 3, "ticketId": ticket_id, "traceId": "trace-reopen-1",
        "correlationId": str(uuid.uuid4()), "causationId": str(uuid.uuid4()), "dataClassification": "INTERNAL",
        "occurredAt": "2026-01-01T00:00:00Z",
        "payload": {
            "supportQueueId": str(uuid.uuid4()), "assigneeId": None, "previousResolutionCycleId": previous_cycle_id,
            "newResolutionCycleId": new_cycle_id, "previousStatus": "RESOLVED", "newStatus": "IN_PROGRESS",
            "reopenReasonCode": "ISSUE_RECURRED", "reopenCount": 1, "reopenedBy": "requester-1",
            "reopenedAt": "2026-01-01T00:00:00Z", "ownershipStatus": "ACTIVE",
        },
    }
    ingested = client.post("/internal/agent-runtime/v1/events/ticket-reopened", json=event_body)

    assert ingested.status_code == 200
    assert ingested.json()["applied"] is True
    assert client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}").json()["state"] == "CANCELLED"


def test_a_malformed_tool_completed_v1_payload_is_recorded_as_a_poison_event_and_stays_replayable(client: TestClient) -> None:
    """SPEC-ARO-024 10-failure-handling §"Poison Event": a payload the type-specific
    consumer cannot even parse is recorded to /admin/poison-events (422, not a raw 500 or
    an unclassified 400) and is NOT marked processed — a corrected redelivery under the
    same event_id must still reach the type-specific consumer.
    """
    workflow_instance_id = _start_workflow(client, "start-poison-1")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-poison-1", "claim_token": claim_token,
    })

    event_body = {
        "event_id": "evt-poison-app-1", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z", "payload": '{"status": "COMPLETED"',  # missing toolRequestId, truncated JSON
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 422
    assert ingested.json()["error"]["code"] == "POISON_EVENT"

    poison_events = client.get("/internal/agent-runtime/v1/admin/poison-events", headers={"X-Actor-Id": "ops-user-1"})
    assert poison_events.status_code == 200
    entries = poison_events.json()["poison_events"]
    assert any(entry["event_id"] == "evt-poison-app-1" and entry["event_type"] == "tool.completed.v1" for entry in entries)

    # Task/workflow untouched by the poisoned delivery.
    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "WAITING_TOOL"


def test_admin_recovery_scan_counts_a_running_workflow_and_flags_nothing(client: TestClient) -> None:
    """SPEC-ARO-028 10-failure-handling §"Runtime 崩溃后怎么恢复": "Recovery worker 周期性
    扫描非终态 Workflow Instance."
    """
    _start_workflow(client, "start-scan-1")

    scanned = client.post("/internal/agent-runtime/v1/admin/workflows/recovery-scan", headers={"X-Actor-Id": "ops-user-1"})
    assert scanned.status_code == 200
    body = scanned.json()
    assert body["scanned"] >= 1
    assert body["checkpoint_inconsistent"] == 0


def test_admin_recovery_scan_flags_and_fails_a_paused_workflow_with_no_pause_point_checkpoint(client: TestClient) -> None:
    """No spec builds an HTTP path into this specific inconsistency (a PAUSED workflow
    whose latest checkpoint isn't the PAUSE_POINT SPEC-ARO-012's Pause Transaction
    writes) — seeded directly, mirroring _seed_waiting_for_verification's own reasoning.
    """
    workflow_instance_id = _start_workflow(client, "start-scan-2")
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


def test_admin_replay_dead_letter_requeues_and_republishes_a_dead_lettered_event(client: TestClient) -> None:
    """SPEC-ARO-030 10-failure-handling §"Runtime 崩溃后怎么恢复" step 3: "重放未发布 outbox"
    — the manual/ops intervention OutboxStatus.DEAD_LETTER's own docstring names. No HTTP
    path can drive a real publish failure (LoggingEventPublisherAdapter always succeeds),
    so a DEAD_LETTER row is seeded directly on the container's repository, mirroring how
    the lease-recovery tests below force their own otherwise-unreachable state.
    """
    workflow_instance_id = _start_workflow(client, "start-outbox-replay-1")
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

    [record] = [r for r in container.outbox_repository.recorded() if r.outbox_id == dead_lettered.outbox_id]
    assert record.status is OutboxStatus.PUBLISHED
    assert record.attempts == 0


def test_admin_force_recover_workflow_flags_a_named_paused_instance(client: TestClient) -> None:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "force recover workflow" — the
    admin-triggered, single-instance counterpart to /workflows/recovery-scan.
    """
    workflow_instance_id = _start_workflow(client, "start-force-recover-1")
    container = get_container()
    running = container.workflow_instance_repository.find_by_id(WorkflowInstanceId(uuid.UUID(workflow_instance_id)))
    container.workflow_instance_repository.save(
        dataclasses.replace(running, state=WorkflowState.PAUSED, pause_generation=1, workflow_version=running.workflow_version + 1)
    )

    forced = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/force-recover", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert forced.status_code == 200
    body = forced.json()
    assert body["scanned"] == 1
    assert body["checkpoint_inconsistent"] == 1

    workflow_after = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}")
    assert workflow_after.json()["state"] == "FAILED"


def test_admin_force_recover_an_unknown_workflow_instance_is_a_404(client: TestClient) -> None:
    forced = client.post(
        f"/internal/agent-runtime/v1/admin/workflows/{uuid.uuid4()}/force-recover", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert forced.status_code == 404


def test_admin_retry_agent_task_retries_a_claimed_task_with_an_unexpired_lease(client: TestClient) -> None:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "retry failed task" — the
    admin-triggered, single-task counterpart to /agent-tasks/lease-recovery-scan; unlike
    the batch scan, this operates even though the lease has not actually expired yet.
    """
    workflow_instance_id = _start_workflow(client, "start-admin-retry-1")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 300,
    })
    assert claimed.status_code == 200
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


def test_admin_retry_agent_task_an_unknown_id_is_a_404(client: TestClient) -> None:
    retried = client.post(
        f"/internal/agent-runtime/v1/admin/agent-tasks/{uuid.uuid4()}/retry", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert retried.status_code == 404


def test_admin_retry_agent_task_rejects_a_terminal_task(client: TestClient) -> None:
    """03-state-machine: FAILED_FINAL is "不可重试失败"."""
    workflow_instance_id = _start_workflow(client, "start-admin-retry-2")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 300,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    workflow_version = claimed.json()["workflow_version"]
    failed = client.post(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "admin-retry-2-fail", "workflow_version": workflow_version,
        "failure_reason": "tool exhausted retries",
    })
    assert failed.status_code == 200
    assert failed.json()["state"] == "FAILED_FINAL"

    retried = client.post(
        f"/internal/agent-runtime/v1/admin/agent-tasks/{agent_task_id}/retry", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert retried.status_code == 409


def test_checkpoint_payload_redacts_a_sensitive_key(client: TestClient) -> None:
    """SPEC-ARO-033 11-security §"Data Protection": "logs 中禁止输出 secret、token、完整
    工具响应" — extended to any outward-facing view that echoes a raw payload, not just
    logs; GET /checkpoints/latest is the one CheckpointView reaches through.
    """
    workflow_instance_id = _start_workflow(client, "start-redact-1")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]

    client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart","token":"super-secret-value"}',
        "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-redact-1", "claim_token": claim_token,
    })

    checkpoint = client.get(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/checkpoints/latest")
    assert checkpoint.status_code == 200
    assert "super-secret-value" not in checkpoint.json()["payload"]
    assert "***REDACTED***" in checkpoint.json()["payload"]
    payload = json.loads(checkpoint.json()["payload"])
    assert payload["before"] == "restart"


def test_admin_poison_event_payload_redacts_a_sensitive_key_even_when_malformed(client: TestClient) -> None:
    """The concrete real-world target of redact_payload(): a poisoned delivery's own
    payload is by definition unparsed/unvalidated content that could carry anything,
    including a still-recognizable sensitive key even inside otherwise-truncated JSON.
    """
    workflow_instance_id = _start_workflow(client, "start-redact-2")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-redact-2", "claim_token": claim_token,
    })
    event_body = {
        "event_id": "evt-redact-1", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z",
        # Malformed (truncated, no closing brace) — what actually makes this poisoned —
        # but the password value is still cleanly quoted, so it must still be masked.
        "payload": '{"password": "hunter2", "status": "COMPLETED"',
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 422

    poison_events = client.get("/internal/agent-runtime/v1/admin/poison-events", headers={"X-Actor-Id": "ops-user-1"})
    [entry] = [e for e in poison_events.json()["poison_events"] if e["event_id"] == "evt-redact-1"]
    assert "hunter2" not in entry["payload"]
    assert "***REDACTED***" in entry["payload"]
    assert "COMPLETED" in entry["payload"]


def test_admin_quarantine_poison_event(client: TestClient) -> None:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined"."""
    workflow_instance_id = _start_workflow(client, "start-quarantine-1")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    client.post("/internal/agent-runtime/v1/agent-tasks/request-tool", json={
        "workflow_instance_id": workflow_instance_id, "agent_task_id": agent_task_id,
        "checkpoint_payload": '{"before":"restart"}', "tool_name": "restart_service", "tool_request_payload": '{"service":"api"}',
        "idempotency_key": "tool-quarantine-1", "claim_token": claim_token,
    })
    event_body = {
        "event_id": "evt-quarantine-1", "event_type": "tool.completed.v1", "producer": "tool-gateway-service", "schema_version": 1,
        "correlation_id": str(uuid.uuid4()), "causation_id": str(uuid.uuid4()), "ticket_id": str(uuid.uuid4()),
        "workflow_instance_id": workflow_instance_id, "expected_workflow_version": None,
        "occurred_at": "2026-01-01T00:00:00Z", "payload": '{"status": "COMPLETED"',
    }
    ingested = client.post("/internal/agent-runtime/v1/events", json=event_body)
    assert ingested.status_code == 422

    poison_events = client.get("/internal/agent-runtime/v1/admin/poison-events", headers={"X-Actor-Id": "ops-user-1"})
    [entry] = [e for e in poison_events.json()["poison_events"] if e["event_id"] == "evt-quarantine-1"]
    assert entry["quarantined_at"] is None

    quarantined = client.post(
        f"/internal/agent-runtime/v1/admin/poison-events/{entry['id']}/quarantine", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert quarantined.status_code == 200
    assert quarantined.json()["quarantined_at"] is not None

    after = client.get("/internal/agent-runtime/v1/admin/poison-events", headers={"X-Actor-Id": "ops-user-1"})
    [entry_after] = [e for e in after.json()["poison_events"] if e["event_id"] == "evt-quarantine-1"]
    assert entry_after["quarantined_at"] is not None


def test_admin_quarantine_an_unknown_poison_event_is_a_404(client: TestClient) -> None:
    quarantined = client.post(
        f"/internal/agent-runtime/v1/admin/poison-events/{uuid.uuid4()}/quarantine", headers={"X-Actor-Id": "ops-user-1"}
    )
    assert quarantined.status_code == 404


def test_admin_lease_recovery_scan_retries_a_task_with_attempts_remaining(client: TestClient) -> None:
    """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5: "对 CLAIMED/RUNNING
    且 lease 过期的 task 做 retry 或 stale 标记" — the retry half. No HTTP path forces a lease
    to expire, so the claim's lease_expires_at is force-set into the past directly on the
    container's repository, mirroring how the checkpoint-inconsistency scan test above
    seeds its own scenario.
    """
    workflow_instance_id = _start_workflow(client, "start-lease-1")
    client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/resume", json={"idempotency_key": "resume-lease-1"})
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert claimed.status_code == 200
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

    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "READY"


def test_admin_lease_recovery_scan_marks_stale_when_attempts_are_exhausted(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-lease-2")
    client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/resume", json={"idempotency_key": "resume-lease-2"})
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert claimed.status_code == 200
    agent_task_id = claimed.json()["agent_task_id"]

    container = get_container()
    claimed_record = container.agent_task_repository.find_by_id(AgentTaskId(uuid.UUID(agent_task_id)))
    container.agent_task_repository.save(dataclasses.replace(
        claimed_record, lease_expires_at=claimed_record.updated_at - timedelta(seconds=1), attempt=claimed_record.max_attempts,
        task_version=claimed_record.task_version + 1,
    ))

    scanned = client.post("/internal/agent-runtime/v1/admin/agent-tasks/lease-recovery-scan", headers={"X-Actor-Id": "ops-user-1"})
    assert scanned.status_code == 200
    body = scanned.json()
    assert body["scanned"] == 1
    assert body["retried"] == 0
    assert body["staled"] == 1

    task_after = client.get(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}")
    assert task_after.json()["state"] == "STALE"


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
    # SPEC-ARO-011 01-domain-model: workflow_version/checksum/cursor are Checkpoint's own
    # minimal fields, now exposed end to end through the existing SPEC-ARO-006 query API.
    assert body["workflow_version"] == 1
    assert body["checksum"]
    assert body["cursor"] is None


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


def test_admin_audit_events_records_a_workflow_transition(client: TestClient) -> None:
    """SPEC-ARO-034 12-observability §"Audit Events": "审计事件必须可长期保存: workflow
    transition, task transition, ..." — starting a workflow must produce a persisted,
    queryable audit row, not just a log line.
    """
    workflow_instance_id = _start_workflow(client, "start-audit-1")

    audit_events = client.get("/internal/agent-runtime/v1/admin/audit-events", headers={"X-Actor-Id": "ops-user-1"})
    assert audit_events.status_code == 200
    entries = audit_events.json()["audit_events"]
    matches = [
        e for e in entries
        if e["workflow_instance_id"] == workflow_instance_id and e["action"] == "start_workflow"
    ]
    assert len(matches) == 1
    entry = matches[0]
    assert entry["audit_type"] == "WORKFLOW_TRANSITION"
    assert entry["resource_type"] == "WorkflowInstance"
    assert entry["outcome"] == "SUCCESS"
    assert entry["actor_type"] == "SYSTEM"


def test_admin_audit_events_records_a_task_claim_and_an_admin_intervention(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "start-audit-2")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    agent_task_id = claimed.json()["agent_task_id"]

    client.post(f"/internal/agent-runtime/v1/admin/agent-tasks/{agent_task_id}/retry", headers={"X-Actor-Id": "ops-user-1"})

    audit_events = client.get("/internal/agent-runtime/v1/admin/audit-events", headers={"X-Actor-Id": "ops-user-1"})
    entries = audit_events.json()["audit_events"]

    claim_entries = [e for e in entries if e["resource_id"] == agent_task_id and e["action"] == "claim_task"]
    assert len(claim_entries) == 1
    assert claim_entries[0]["audit_type"] == "TASK_TRANSITION"
    assert claim_entries[0]["actor_type"] == "WORKER"
    assert claim_entries[0]["actor_id"] == "worker-1"

    admin_entries = [e for e in entries if e["resource_id"] == agent_task_id and e["audit_type"] == "ADMIN_INTERVENTION"]
    assert len(admin_entries) == 1
    assert admin_entries[0]["actor_type"] == "ADMIN"


def test_admin_audit_events_respects_the_limit(client: TestClient) -> None:
    for i in range(3):
        _start_workflow(client, f"start-audit-limit-{i}")

    limited = client.get("/internal/agent-runtime/v1/admin/audit-events?limit=1", headers={"X-Actor-Id": "ops-user-1"})
    assert limited.status_code == 200
    assert len(limited.json()["audit_events"]) == 1
