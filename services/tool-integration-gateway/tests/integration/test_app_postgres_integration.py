"""SPEC-TG-002 acceptance-criteria: the same acceptance walk as tests/test_app.py,
but wired to the real, migrated `tool` Postgres schema instead of SPEC-TG-001's
in-memory adapters — proves the schema baseline is not just created, but actually
load-bearing for the whole request lifecycle. Mirrors memory-knowledge-service's
own tests/integration/test_app_postgres_integration.py.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from tool_gateway.container import get_container
from tool_gateway.domain.ids import ConnectorId
from tool_gateway.main import create_app
from tool_gateway.settings import Settings

pytestmark = pytest.mark.integration


@pytest.fixture
def client(migrated_engine, monkeypatch: pytest.MonkeyPatch):
    url = migrated_engine.url
    settings = Settings(
        db_host=url.host, db_port=url.port, db_name=url.database, db_username=url.username, db_password=url.password,
        tool_gateway_persistence="postgres",
    )
    monkeypatch.setattr("tool_gateway.container.get_settings", lambda: settings)
    get_container.cache_clear()
    return TestClient(create_app())


def _register_connector(client: TestClient, capability: str, risk_level: str = "LOW", is_mutating: bool = False) -> str:
    response = client.post("/internal/tool-gateway/v1/connectors", json={
        "name": f"connector-for-{capability}", "version": "1.0.0", "capability_names": [capability],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": risk_level,
        "requires_approval": risk_level != "LOW", "is_mutating": is_mutating, "correlation_id": str(uuid.uuid4()),
    })
    assert response.status_code == 200
    return response.json()["connector_id"]


def test_low_risk_capability_completes_against_real_postgres(client: TestClient) -> None:
    """04-use-cases UC-TG-001 + UC-TG-002 end to end against a real, migrated
    Postgres schema — proves ToolRequestRepository/ToolExecutionRepository/
    ResultEnvelopeRepository/ConnectorRepository are actually written, not just
    the in-memory adapters.
    """

    _register_connector(client, "kubernetes.getPodLogs")

    submitted = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs", "input_payload": {"pod": "app-1"}, "reason": "investigate crash loop",
        "correlation_id": str(uuid.uuid4()),
    })
    assert submitted.status_code == 200
    tool_request_id = submitted.json()["tool_request_id"]
    assert submitted.json()["status"] == "QUEUED"

    # SPEC-TG-005 05-api-contracts §"Error Model": a nonexistent result envelope
    # comes back as a structured RESULT_NOT_FOUND 404, not a raw exception.
    unresolved = client.get(f"/internal/tool-gateway/v1/tool-results/{uuid.uuid4()}")
    assert unresolved.status_code == 404
    assert unresolved.json()["error"]["code"] == "RESULT_NOT_FOUND"

    from tool_gateway.application.commands import ExecuteToolRequestCommand

    executed = get_container().execute_tool_request_port.execute_tool_request(
        ExecuteToolRequestCommand(tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4())),
    )
    assert executed.status == "COMPLETED"

    # 05-api-contracts §"Result API": GET /tool-results/{resultEnvelopeId} —
    # keyed by the result envelope's own id, reached via the completed
    # ToolRequest's own resultEnvelopeId field.
    result_envelope_id = client.get(f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}").json()["result_envelope_id"]
    result = client.get(f"/internal/tool-gateway/v1/tool-results/{result_envelope_id}")
    assert result.status_code == 200
    assert result.json()["status"] == "SUCCESS"


def test_high_risk_capability_waits_for_approval_against_real_postgres(client: TestClient) -> None:
    """04-use-cases UC-TG-003 end to end against a real, migrated Postgres schema."""

    _register_connector(client, "kubernetes.restartDeployment", risk_level="HIGH", is_mutating=True)

    submitted = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.restartDeployment", "input_payload": {"deployment": "checkout"},
        "reason": "clear stuck pods", "correlation_id": str(uuid.uuid4()),
    })
    assert submitted.status_code == 200
    tool_request_id = submitted.json()["tool_request_id"]
    assert submitted.json()["status"] == "WAITING_APPROVAL"

    approved = client.post(f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}/approval-decisions", json={
        "approved": True, "decided_by": "approver-1", "correlation_id": str(uuid.uuid4()),
    })
    assert approved.status_code == 200
    assert approved.json()["status"] == "QUEUED"


def test_duplicate_idempotency_key_returns_the_same_request_against_real_postgres(client: TestClient) -> None:
    """09-concurrency-and-idempotency §"Tool Request Idempotency" against the real
    uq_tool_requests_workflow_task_idempotency_key unique constraint.
    """

    _register_connector(client, "kubernetes.getPodLogs")
    body = {
        "idempotency_key": "idem-shared-pg", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs", "input_payload": {"pod": "app-1"}, "reason": "investigate crash loop",
        "correlation_id": str(uuid.uuid4()),
    }
    first = client.post("/internal/tool-gateway/v1/tool-requests", json=body)
    second = client.post("/internal/tool-gateway/v1/tool-requests", json=body)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["tool_request_id"] == second.json()["tool_request_id"]


def test_different_payload_under_same_idempotency_key_conflicts_against_real_postgres(client: TestClient) -> None:
    _register_connector(client, "kubernetes.getPodLogs")
    shared_key = f"idem-conflict-{uuid.uuid4()}"
    first = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": shared_key, "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs", "input_payload": {"pod": "app-1"}, "reason": "investigate crash loop",
        "correlation_id": str(uuid.uuid4()),
    })
    assert first.status_code == 200

    # 05-api-contracts §"Runtime API": "If payload hash differs, return 409
    # IDEMPOTENCY_CONFLICT."
    conflicting = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": shared_key, "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs", "input_payload": {"pod": "app-2"}, "reason": "different investigation",
        "correlation_id": str(uuid.uuid4()),
    })
    assert conflicting.status_code == 409
    assert conflicting.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"


def test_cancel_queued_request_against_real_postgres(client: TestClient) -> None:
    _register_connector(client, "servicenow.openChangeRequest", is_mutating=True)
    submitted = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "servicenow.openChangeRequest", "input_payload": {}, "reason": "open change",
        "correlation_id": str(uuid.uuid4()),
    })
    tool_request_id = submitted.json()["tool_request_id"]
    cancel_body = {
        "idempotency_key": f"cancel-{uuid.uuid4()}", "requested_by": "agent-1", "reason": "no longer needed",
        "correlation_id": str(uuid.uuid4()),
    }

    cancelled = client.post(f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}/cancel", json=cancel_body)
    assert cancelled.status_code == 200
    assert cancelled.json()["status"] == "CANCELLED"

    # 05-api-contracts: cancel "Requires idempotencyKey and requester" — a
    # repeat call is a no-op, not an INVALID_STATE_TRANSITION conflict.
    repeated = client.post(f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}/cancel", json=cancel_body)
    assert repeated.status_code == 200
    assert repeated.json()["status"] == "CANCELLED"


def test_connector_list_survives_a_container_rebuild_against_real_postgres(client: TestClient) -> None:
    """Proves this is durable storage, not the SPEC-TG-001 in-memory adapters
    silently still in play: a fresh Container (as a new process would build)
    still finds the connector a previous Container instance registered.
    """

    connector_id = _register_connector(client, "slack.notifyChannel", is_mutating=True)

    get_container.cache_clear()
    new_client = TestClient(create_app())
    listed = new_client.get("/internal/tool-gateway/v1/connectors")
    assert listed.status_code == 200
    assert any(c["connector_id"] == connector_id for c in listed.json())


def test_outbox_dispatch_publishes_accepted_event_against_real_postgres(client: TestClient) -> None:
    """00-implementation-roadmap §"Closure Principles": "Every published event
    must go through Gateway outbox" — against real outbox_events rows.
    """

    _register_connector(client, "slack.notifyChannel", is_mutating=True)
    client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "slack.notifyChannel", "input_payload": {}, "reason": "notify oncall",
        "correlation_id": str(uuid.uuid4()),
    })

    from tool_gateway.application.commands import DispatchOutboxCommand

    published = get_container().publish_outbox_port.dispatch(DispatchOutboxCommand(batch_size=10))
    assert published >= 1


def test_audit_records_are_persisted_against_real_postgres(client: TestClient) -> None:
    """INV-TG-006: "Audit records are mandatory" — proves ToolAuditRecordRow is
    real DDL, not just the in-memory adapter's own list.
    """

    _register_connector(client, "kubernetes.getPodLogs")
    client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs", "input_payload": {}, "reason": "investigate", "correlation_id": str(uuid.uuid4()),
    })

    recent = get_container().audit_record_repository.find_recent(10)
    assert any(entry.action == "request_accepted" for entry in recent)


def test_approval_linkage_survives_a_container_rebuild_against_real_postgres(client: TestClient) -> None:
    """SPEC-TG-009 09-concurrency-and-idempotency §"Approval Event Idempotency":
    the incoming event's ``approvalRequestId`` must match the ToolRequest's own
    stored linkage — proves that check is load-bearing against a real,
    migrated Postgres schema (adapters.db.postgres_repositories._row_to_approval_ref
    fixed a real round-trip gap: approval_ref used to always come back None
    after a reload), not just the in-memory adapter which never lost it in the
    first place.
    """

    _register_connector(client, "kubernetes.restartPg", risk_level="HIGH", is_mutating=True)
    submitted = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.restartPg", "input_payload": {}, "reason": "clear stuck pods",
        "correlation_id": str(uuid.uuid4()),
    })
    body = submitted.json()
    assert body["status"] == "WAITING_APPROVAL"
    approval_request_id = body["approval_request_id"]
    assert approval_request_id is not None

    get_container.cache_clear()
    new_client = TestClient(create_app())

    mismatched = new_client.post("/internal/tool-gateway/v1/events/approval-granted", json={
        "event_id": f"evt-{uuid.uuid4()}", "approval_request_id": str(uuid.uuid4()), "tool_request_id": body["tool_request_id"],
        "approved_by": "approver-1", "correlation_id": str(uuid.uuid4()),
    })
    assert mismatched.status_code == 403
    assert mismatched.json()["error"]["code"] == "APPROVAL_LINKAGE_MISMATCH"

    granted = new_client.post("/internal/tool-gateway/v1/events/approval-granted", json={
        "event_id": f"evt-{uuid.uuid4()}", "approval_request_id": approval_request_id, "tool_request_id": body["tool_request_id"],
        "approved_by": "approver-1", "correlation_id": str(uuid.uuid4()),
    })
    assert granted.status_code == 200
    assert granted.json()["status"] == "QUEUED"


def test_allowed_requester_types_survives_a_container_rebuild_against_real_postgres(client: TestClient) -> None:
    """SPEC-TG-021 INV-TG-009: a connector's requester-type restriction is a
    real security control — before this spec, ``tool_connectors.manifest_json``
    was always written as ``{}`` and this field would have silently reset to
    "unrestricted" on every reload; this proves it actually round-trips
    through the real Postgres row, not just the in-memory adapter (which never
    loses it in the first place since it stores whole objects).
    """

    register_response = client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "human-only-connector-pg", "version": "1.0.0", "capability_names": ["kubernetes.humanOnlyPg"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "allowed_requester_types": ["HUMAN_OPERATOR"],
        "correlation_id": str(uuid.uuid4()),
    })
    assert register_response.status_code == 200

    get_container.cache_clear()
    new_client = TestClient(create_app())

    denied = new_client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "triage-agent",
        "capability_name": "kubernetes.humanOnlyPg", "input_payload": {}, "reason": "try it",
        "correlation_id": str(uuid.uuid4()),
    })
    assert denied.json()["status"] == "REJECTED"

    allowed = new_client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "HUMAN_OPERATOR", "requested_by_id": "admin-1",
        "capability_name": "kubernetes.humanOnlyPg", "input_payload": {}, "reason": "try it",
        "correlation_id": str(uuid.uuid4()),
    })
    # HTTP chains create+evaluate synchronously (04-use-cases UC-TG-001+002) —
    # a LOW-risk, auto-approved request reaches QUEUED, not just VALIDATING.
    assert allowed.json()["status"] == "QUEUED"


def test_connector_consecutive_health_check_failures_survives_a_container_rebuild_against_real_postgres(
    client: TestClient,
) -> None:
    """SPEC-TG-030 10-failure-handling §"Connector Crash Or Unavailability":
    ``consecutive_health_check_failures`` reuses the same ``manifest_json``
    slot ``allowed_requester_types`` already established (see
    ``adapters.db.postgres_repositories._connector_to_row_values``'s own
    docstring) — this proves the counter and the DEGRADED escalation it
    drove both round-trip through a real Postgres reload, not just the
    in-process container the health checks actually ran against.
    """

    register_response = client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "flaky-connector-pg", "version": "1.0.0", "capability_names": ["kubernetes.flakyPg"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "correlation_id": str(uuid.uuid4()),
    })
    assert register_response.status_code == 200
    connector_id = register_response.json()["connector_id"]

    container = get_container()
    for _ in range(3):
        container.register_connector_service.apply_health_check_result(
            ConnectorId(uuid.UUID(connector_id)), healthy=False, correlation_id=str(uuid.uuid4()),
        )

    get_container.cache_clear()
    new_client = TestClient(create_app())
    reloaded = new_client.get(f"/internal/tool-gateway/v1/connectors/{connector_id}")
    assert reloaded.status_code == 200
    body = reloaded.json()
    assert body["health_status"] == "DEGRADED"
    assert body["consecutive_health_check_failures"] == 3
