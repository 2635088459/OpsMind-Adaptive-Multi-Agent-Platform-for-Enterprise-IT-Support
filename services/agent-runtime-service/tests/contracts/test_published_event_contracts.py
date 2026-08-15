"""SPEC-ARO-035 14-testing-strategy §"契约测试": the six published event types
(workflow.started.v1, workflow.paused.v1, workflow.resumed.v1, agent.task.completed.v1,
workflow.completed.v1, workflow.failed.v1).

Drives the real application through a real FastAPI TestClient/real container (the exact
same pattern tests/test_app.py's own HTTP-level tests already use), inspects the real
OutboxRecord each service actually appended (container.outbox_repository.recorded()) —
not a hand-built stand-in record — and runs it through the REAL envelope-assembly code
(RabbitMqEventPublisherAdapter._to_envelope_json(), the one production seam every
published event is actually serialized by before hitting the broker) rather than
re-implementing envelope construction a second time in test code. The result is validated
against EnvelopeContract (06-event-contracts §"Envelope") and the event-type-specific
payload contract (tests/contracts/schemas.py). If a future change to any publishing
service or to the envelope adapter itself drifts from either contract, this file fails —
tests/integration/test_rabbitmq_event_publisher.py already proves the envelope reaches a
real broker byte-for-byte; this file is the complementary proof that its *contents*, for
each of the six named event types, are what 06-event-contracts promises.
"""

from __future__ import annotations

import json
import uuid

import pytest
from fastapi.testclient import TestClient

from agentruntime.application.records import OutboxRecord
from agentruntime.container import get_container
from agentruntime.infrastructure.event_publisher_rabbitmq import RabbitMqEventPublisherAdapter
from agentruntime.main import create_app
from agentruntime.settings import Settings
from tests.contracts.schemas import (
    AgentTaskCompletedPayloadContract,
    EnvelopeContract,
    WorkflowCompletedPayloadContract,
    WorkflowFailedPayloadContract,
    WorkflowPausedPayloadContract,
    WorkflowResumedPayloadContract,
    WorkflowStartedPayloadContract,
)

pytestmark = pytest.mark.unit

_ADAPTER = RabbitMqEventPublisherAdapter(Settings())  # _to_envelope_json() is pure — no broker connection is ever opened.


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("agentruntime.container.get_settings", lambda: Settings(agent_runtime_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


def _envelope(record: OutboxRecord) -> dict:
    envelope = json.loads(_ADAPTER._to_envelope_json(record))
    EnvelopeContract(**envelope)
    return envelope


def _latest(client: TestClient, event_type: str) -> OutboxRecord:
    container = get_container()
    matching = [r for r in container.outbox_repository.recorded() if r.event_type == event_type]
    assert matching, f"expected at least one recorded OutboxRecord of type {event_type!r}"
    return max(matching, key=lambda r: r.occurred_at)


def _start_workflow(client: TestClient, idempotency_key: str) -> str:
    response = client.post("/internal/agent-runtime/v1/workflows", json={
        "ticket_id": str(uuid.uuid4()), "ticket_cycle_id": str(uuid.uuid4()),
        "workflow_definition_id": "triage-v1", "definition_version": 1, "workflow_type": "TICKET_TRIAGE",
        "task_graph": [{"task_key": "collect", "task_type": "collect_diagnostics", "depends_on": [], "join_policy": "ALL_SUCCESS"}],
        "idempotency_key": idempotency_key,
    })
    assert response.status_code == 201, response.text
    return response.json()["workflow_instance_id"]


def test_workflow_started_conforms_to_its_contract(client: TestClient) -> None:
    _start_workflow(client, "contract-start-1")

    envelope = _envelope(_latest(client, "workflow.started.v1"))

    assert envelope["eventType"] == "workflow.started.v1"
    WorkflowStartedPayloadContract(**envelope["payload"])


def test_workflow_paused_conforms_to_its_contract(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "contract-pause-1")
    response = client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/pause", json={"idempotency_key": "pause-1"})
    assert response.status_code == 200, response.text

    envelope = _envelope(_latest(client, "workflow.paused.v1"))

    assert envelope["eventType"] == "workflow.paused.v1"
    WorkflowPausedPayloadContract(**envelope["payload"])


def test_workflow_resumed_conforms_to_its_contract(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "contract-resume-1")
    client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/pause", json={"idempotency_key": "pause-1"})
    response = client.post(f"/internal/agent-runtime/v1/workflows/{workflow_instance_id}/resume", json={"idempotency_key": "resume-1"})
    assert response.status_code == 200, response.text

    envelope = _envelope(_latest(client, "workflow.resumed.v1"))

    assert envelope["eventType"] == "workflow.resumed.v1"
    WorkflowResumedPayloadContract(**envelope["payload"])


def test_agent_task_completed_and_workflow_completed_conform_to_their_contracts(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "contract-complete-1")
    claimed = client.post("/internal/agent-runtime/v1/agent-tasks/claim", json={
        "workflow_instance_id": workflow_instance_id, "task_key": "collect", "worker_id": "worker-1", "lease_seconds": 60,
    })
    assert claimed.status_code == 200, claimed.text
    agent_task_id = claimed.json()["agent_task_id"]
    claim_token = claimed.json()["claim_token"]
    workflow_version = claimed.json()["workflow_version"]

    completed = client.post(f"/internal/agent-runtime/v1/agent-tasks/{agent_task_id}/complete", json={
        "claim_token": claim_token, "idempotency_key": "contract-task-complete-1", "workflow_version": workflow_version,
        "result_payload": "diagnostics collected",
    })
    assert completed.status_code == 200, completed.text

    task_envelope = _envelope(_latest(client, "agent.task.completed.v1"))
    assert task_envelope["eventType"] == "agent.task.completed.v1"
    AgentTaskCompletedPayloadContract(**task_envelope["payload"])

    # The task graph's only task just completed, so CompleteAgentTaskService's own
    # auto-settlement publishes workflow.completed.v1 in the same call — no separate
    # admin trigger needed for this event type.
    workflow_envelope = _envelope(_latest(client, "workflow.completed.v1"))
    assert workflow_envelope["eventType"] == "workflow.completed.v1"
    WorkflowCompletedPayloadContract(**workflow_envelope["payload"])


def test_workflow_failed_conforms_to_its_contract(client: TestClient) -> None:
    workflow_instance_id = _start_workflow(client, "contract-fail-1")

    response = client.post(f"/internal/agent-runtime/v1/admin/workflows/{workflow_instance_id}/fail", json={
        "idempotency_key": "contract-fail-op-1", "failure_reason": "contract-harness induced failure",
    })
    assert response.status_code == 200, response.text

    envelope = _envelope(_latest(client, "workflow.failed.v1"))

    assert envelope["eventType"] == "workflow.failed.v1"
    WorkflowFailedPayloadContract(**envelope["payload"])
