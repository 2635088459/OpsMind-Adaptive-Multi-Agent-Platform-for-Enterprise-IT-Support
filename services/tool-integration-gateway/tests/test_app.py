"""End-to-end smoke test through the real FastAPI app — mirrors
memory-knowledge-service's own tests/test_app.py convention. Exercises
INV-TG-001 (Gateway is the only tool execution entry point) via the actual
HTTP surface, not direct application-service calls.
"""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from tool_gateway.application.commands import ExecuteToolRequestCommand
from tool_gateway.container import get_container
from tool_gateway.main import create_app
from tool_gateway.settings import Settings


@pytest.fixture()
def client(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    # SPEC-TG-002 made "postgres" the container's real-run default
    # (Settings.tool_gateway_persistence) — this test stays on SPEC-TG-001's
    # in-memory adapters (fast, hermetic, no Docker); see
    # tests/integration/test_postgres_repositories.py for the same kind of
    # coverage against a real Postgres.
    monkeypatch.setattr("tool_gateway.container.get_settings", lambda: Settings(tool_gateway_persistence="memory"))
    get_container.cache_clear()
    return TestClient(create_app())


def test_health(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_register_connector_then_submit_and_execute_tool_request(client: TestClient) -> None:
    register_response = client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "kubernetes-connector", "version": "1.0.0", "capability_names": ["kubernetes.getPodLogs"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "correlation_id": str(uuid.uuid4()),
    })
    assert register_response.status_code == 200
    assert register_response.json()["health_status"] == "ACTIVE"

    submit_response = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": "idem-e2e-1", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs", "input_payload": {"pod": "app-1"}, "reason": "investigate crash loop",
        "correlation_id": str(uuid.uuid4()),
    })
    assert submit_response.status_code == 200
    body = submit_response.json()
    assert body["status"] == "QUEUED"
    tool_request_id = body["tool_request_id"]

    get_response = client.get(f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}")
    assert get_response.status_code == 200
    assert get_response.json()["status"] == "QUEUED"


def test_submit_for_unregistered_capability_is_rejected(client: TestClient) -> None:
    response = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": "idem-e2e-2", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "no.such.capability", "input_payload": {}, "reason": "try something",
        "correlation_id": str(uuid.uuid4()),
    })
    assert response.status_code == 200
    assert response.json()["status"] == "REJECTED"


def test_duplicate_idempotency_key_with_different_payload_returns_409(client: TestClient) -> None:
    """05-api-contracts §"Runtime API": "If payload hash differs, return 409
    IDEMPOTENCY_CONFLICT."
    """

    client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "kubernetes-connector-2", "version": "1.0.0", "capability_names": ["kubernetes.getPodLogs2"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "correlation_id": str(uuid.uuid4()),
    })
    shared_key = f"idem-conflict-{uuid.uuid4()}"
    first = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": shared_key, "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs2", "input_payload": {"pod": "app-1"}, "reason": "investigate",
        "correlation_id": str(uuid.uuid4()),
    })
    assert first.status_code == 200

    conflicting = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": shared_key, "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogs2", "input_payload": {"pod": "app-2"}, "reason": "different",
        "correlation_id": str(uuid.uuid4()),
    })
    assert conflicting.status_code == 409
    assert conflicting.json()["error"]["code"] == "IDEMPOTENCY_CONFLICT"


def test_connector_admin_status_lifecycle_and_capability_visibility(client: TestClient) -> None:
    """05-api-contracts §"Connector Admin API": ``PATCH /connectors/
    {connectorId}/status`` and ``GET /capabilities``.
    """

    registered = client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "slack-connector", "version": "1.0.0", "capability_names": ["slack.postMessage2"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": True, "correlation_id": str(uuid.uuid4()),
    })
    connector_id = registered.json()["connector_id"]

    visible = client.get("/internal/tool-gateway/v1/capabilities")
    assert visible.status_code == 200
    assert any(c["capability_name"] == "slack.postMessage2" for c in visible.json())

    disabled = client.patch(f"/internal/tool-gateway/v1/connectors/{connector_id}/status", json={
        "action": "DISABLE", "requested_by": "admin-1", "correlation_id": str(uuid.uuid4()),
    })
    assert disabled.status_code == 200
    assert disabled.json()["health_status"] == "DISABLED"

    hidden = client.get("/internal/tool-gateway/v1/capabilities")
    assert not any(c["capability_name"] == "slack.postMessage2" for c in hidden.json())

    invalid_action = client.patch(f"/internal/tool-gateway/v1/connectors/{connector_id}/status", json={
        "action": "NOT_A_REAL_ACTION", "requested_by": "admin-1", "correlation_id": str(uuid.uuid4()),
    })
    assert invalid_action.status_code == 400
    assert invalid_action.json()["error"]["code"] == "VALIDATION_FAILED"


def test_policy_denied_capability_via_http(client: TestClient) -> None:
    """SPEC-TG-007 10-failure-handling §"Policy / Approval Failure"."""

    client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "wipe-connector", "version": "1.0.0", "capability_names": ["kubernetes.wipeClusterHttp"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": True, "correlation_id": str(uuid.uuid4()),
    })
    submitted = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.wipeClusterHttp", "input_payload": {}, "reason": "clean slate",
        "correlation_id": str(uuid.uuid4()),
    })
    assert submitted.status_code == 200
    assert submitted.json()["status"] == "POLICY_DENIED"


def test_approval_granted_and_denied_events_via_http(client: TestClient) -> None:
    """SPEC-TG-008/SPEC-TG-009 06-event-contracts §"approval.granted.v1"/
    §"approval.denied.v1".
    """

    client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "restart-connector", "version": "1.0.0", "capability_names": ["kubernetes.restartHttp"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "HIGH",
        "requires_approval": True, "is_mutating": True, "correlation_id": str(uuid.uuid4()),
    })

    granted_submit = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.restartHttp", "input_payload": {}, "reason": "clear stuck pods",
        "correlation_id": str(uuid.uuid4()),
    })
    granted_body = granted_submit.json()
    assert granted_body["status"] == "WAITING_APPROVAL"
    assert granted_body["approval_request_id"] is not None

    granted = client.post("/internal/tool-gateway/v1/events/approval-granted", json={
        "event_id": f"evt-{uuid.uuid4()}", "approval_request_id": granted_body["approval_request_id"],
        "tool_request_id": granted_body["tool_request_id"], "approved_by": "approver-1",
        "correlation_id": str(uuid.uuid4()),
    })
    assert granted.status_code == 200
    assert granted.json()["status"] == "QUEUED"

    denied_submit = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.restartHttp", "input_payload": {}, "reason": "clear stuck pods again",
        "correlation_id": str(uuid.uuid4()),
    })
    denied_body = denied_submit.json()

    denied = client.post("/internal/tool-gateway/v1/events/approval-denied", json={
        "event_id": f"evt-{uuid.uuid4()}", "approval_request_id": denied_body["approval_request_id"],
        "tool_request_id": denied_body["tool_request_id"], "denied_by": "approver-1", "denial_reason": "too risky",
        "correlation_id": str(uuid.uuid4()),
    })
    assert denied.status_code == 200
    assert denied.json()["status"] == "APPROVAL_DENIED"

    # 09-concurrency-and-idempotency: a mismatched approvalRequestId against a
    # request still genuinely WAITING_APPROVAL is rejected with a
    # security-audited 403, never silently applied.
    mismatch_submit = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.restartHttp", "input_payload": {}, "reason": "clear stuck pods yet again",
        "correlation_id": str(uuid.uuid4()),
    })
    mismatch_body = mismatch_submit.json()
    assert mismatch_body["status"] == "WAITING_APPROVAL"

    mismatched = client.post("/internal/tool-gateway/v1/events/approval-granted", json={
        "event_id": f"evt-{uuid.uuid4()}", "approval_request_id": str(uuid.uuid4()),
        "tool_request_id": mismatch_body["tool_request_id"], "approved_by": "approver-1",
        "correlation_id": str(uuid.uuid4()),
    })
    assert mismatched.status_code == 403
    assert mismatched.json()["error"]["code"] == "APPROVAL_LINKAGE_MISMATCH"


def test_policy_rule_changed_event_is_idempotent_via_http(client: TestClient) -> None:
    body = {"event_id": f"evt-{uuid.uuid4()}", "rule_id": "rule-1", "correlation_id": str(uuid.uuid4())}
    first = client.post("/internal/tool-gateway/v1/events/policy-rule-changed", json=body)
    second = client.post("/internal/tool-gateway/v1/events/policy-rule-changed", json=body)
    assert first.status_code == 200
    assert first.json()["applied"] is True
    assert second.status_code == 200
    assert second.json()["applied"] is False


def test_raw_output_endpoint_requires_human_operator_and_reason(client: TestClient) -> None:
    """SPEC-TG-020 05-api-contracts §"Result API": ``GET /tool-results/{id}
    /raw`` — exercises the real HTTP route (headers, status codes, error
    body) end to end; business-logic coverage (redaction/storage/audit) lives
    in tests/application/test_full_flow.py.
    """

    register_response = client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "kubernetes-connector-raw", "version": "1.0.0", "capability_names": ["kubernetes.getPodLogsRaw"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "correlation_id": str(uuid.uuid4()),
    })
    assert register_response.status_code == 200

    submit_response = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogsRaw", "input_payload": {"pod": "app-1"}, "reason": "investigate",
        "correlation_id": str(uuid.uuid4()),
    })
    tool_request_id = submit_response.json()["tool_request_id"]

    # Execution is worker-driven, not HTTP-triggered (13-package-and-class-
    # design §"Internal Worker API": "workers do not mutate state through
    # public HTTP APIs") — drive it directly through the same container the
    # HTTP app itself resolves, mirroring how a real ExecutionWorker would.
    executed = get_container().execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    result_envelope_id = executed.result_envelope_id

    forbidden = client.get(
        f"/internal/tool-gateway/v1/tool-results/{result_envelope_id}/raw", params={"reason": "peeking"},
        headers={"X-Requested-By-Type": "AGENT", "X-Requested-By-Id": "triage-agent"},
    )
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "RAW_OUTPUT_FORBIDDEN"

    granted = client.get(
        f"/internal/tool-gateway/v1/tool-results/{result_envelope_id}/raw", params={"reason": "investigating INC-1"},
        headers={"X-Requested-By-Type": "HUMAN_OPERATOR", "X-Requested-By-Id": "admin-1"},
    )
    assert granted.status_code == 200
    # EchoConnectorAdapter never produces raw output — a legitimate null, not
    # a denial (see the module-level test for the actually-populated case).
    assert granted.json()["raw_output"] is None


def test_workflow_cancelled_event_cancels_queued_tool_requests_via_http(client: TestClient) -> None:
    """SPEC-TG-022 06-event-contracts §"workflow.cancelled.v1"."""

    client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "kubernetes-connector-wf-cancel", "version": "1.0.0", "capability_names": ["kubernetes.getPodLogsWfCancel"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "correlation_id": str(uuid.uuid4()),
    })
    workflow_instance_id = str(uuid.uuid4())
    submitted = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getPodLogsWfCancel", "input_payload": {}, "reason": "investigate",
        "correlation_id": str(uuid.uuid4()), "workflow_instance_id": workflow_instance_id,
    })
    tool_request_id = submitted.json()["tool_request_id"]
    assert submitted.json()["status"] == "QUEUED"

    response = client.post("/internal/tool-gateway/v1/events/workflow-cancelled", json={
        "event_id": f"evt-{uuid.uuid4()}", "workflow_instance_id": workflow_instance_id, "correlation_id": str(uuid.uuid4()),
    })
    assert response.status_code == 200
    assert response.json()["cancelled_count"] == 1

    reloaded = client.get(f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}")
    assert reloaded.json()["status"] == "CANCELLED"


def test_connector_lookup_by_id_returns_full_manifest_via_http(client: TestClient) -> None:
    """SPEC-TG-029 "Connector Admin Lifecycle API": ``GET /connectors/{id}``."""

    registered = client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "kubernetes-connector-lookup", "version": "1.0.0", "capability_names": ["kubernetes.lookupOp"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "secret_requirements": ["api-token"],
        "allowed_hosts": ["api.internal.example"], "correlation_id": str(uuid.uuid4()),
    })
    connector_id = registered.json()["connector_id"]

    found = client.get(f"/internal/tool-gateway/v1/connectors/{connector_id}")
    assert found.status_code == 200
    body = found.json()
    assert body["connector_id"] == connector_id
    assert body["secret_requirements"] == ["api-token"]
    assert body["allowed_hosts"] == ["api.internal.example"]

    # ConnectorNotFoundException is pre-existing (SPEC-TG-003's own registry
    # lookup) and maps to 503/CONNECTOR_UNAVAILABLE, not 404 — this admin
    # lookup reuses that same exception/handler rather than introducing a
    # second not-found meaning for the same connector_id concept.
    missing = client.get(f"/internal/tool-gateway/v1/connectors/{uuid.uuid4()}")
    assert missing.status_code == 503
    assert missing.json()["error"]["code"] == "CONNECTOR_UNAVAILABLE"


def test_admin_audit_query_and_outbox_dead_letter_replay_via_http(client: TestClient) -> None:
    """SPEC-TG-027/SPEC-TG-028 admin surfaces."""

    client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "kubernetes-connector-admin", "version": "1.0.0", "capability_names": ["kubernetes.adminAuditOp"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "correlation_id": str(uuid.uuid4()),
    })
    client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "admin-audit-agent",
        "capability_name": "kubernetes.adminAuditOp", "input_payload": {}, "reason": "investigate",
        "correlation_id": str(uuid.uuid4()),
    })

    by_actor = client.get("/internal/tool-gateway/v1/admin/audit", params={"actor_id": "admin-audit-agent"})
    assert by_actor.status_code == 200
    assert any(e["actor_id"] == "admin-audit-agent" for e in by_actor.json())

    recent = client.get("/internal/tool-gateway/v1/admin/audit")
    assert recent.status_code == 200
    assert recent.json()

    empty_dead_letter = client.get("/internal/tool-gateway/v1/admin/outbox/dead-letter")
    assert empty_dead_letter.status_code == 200
    assert empty_dead_letter.json() == []

    replay_missing = client.post(
        f"/internal/tool-gateway/v1/admin/outbox/{uuid.uuid4()}/replay", json={"requested_by": "admin-1"},
    )
    assert replay_missing.status_code == 404
    assert replay_missing.json()["error"]["code"] == "OUTBOX_RECORD_NOT_FOUND"


def test_admin_recovery_run_via_http(client: TestClient) -> None:
    """SPEC-TG-030 "Crash Recovery Backpressure Scaling" 10-failure-handling
    §"Gateway Crash Recovery": ``POST /admin/recovery/run`` — a no-op-shaped
    but real call against an otherwise-empty gateway (nothing lease-expired,
    nothing pending in the outbox at this point in the test) still returns a
    well-formed zero summary rather than erroring.
    """

    response = client.post("/internal/tool-gateway/v1/admin/recovery/run")
    assert response.status_code == 200
    body = response.json()
    assert body["leases_reclaimed"] == 0
    assert body["outbox_events_published"] == 0


def test_agent_facing_responses_never_include_a_credential_vault_reference(client: TestClient) -> None:
    """SPEC-TG-032 final coverage audit / 14-testing-strategy §"Security
    Tests": "Agent cannot read credential/vault ref." ``ToolRequestResponse``/
    ``ToolResultResponse`` (``api/schemas.py``) never declare a vault/
    credential field in the first place — this is a regression guard proving
    that stays true end to end for a capability that genuinely resolves a
    credential binding (``InMemoryVaultCredentialAdapter``'s own
    ``vault://tool-gateway/...`` ref format), not just an inspection of the
    schema.
    """

    register_response = client.post("/internal/tool-gateway/v1/connectors", json={
        "name": "kubernetes-connector-secret", "version": "1.0.0", "capability_names": ["kubernetes.getSecretBackedLogs"],
        "input_schema_ref": "schema://input/v1", "output_schema_ref": "schema://output/v1", "risk_level": "LOW",
        "requires_approval": False, "is_mutating": False, "secret_requirements": ["api-token"],
        "correlation_id": str(uuid.uuid4()),
    })
    assert register_response.status_code == 200

    submit_response = client.post("/internal/tool-gateway/v1/tool-requests", json={
        "idempotency_key": f"idem-{uuid.uuid4()}", "requested_by_type": "AGENT", "requested_by_id": "agent-1",
        "capability_name": "kubernetes.getSecretBackedLogs", "input_payload": {"pod": "app-1"}, "reason": "investigate",
        "correlation_id": str(uuid.uuid4()),
    })
    tool_request_id = submit_response.json()["tool_request_id"]

    # Execution is worker-driven, not HTTP-triggered — see
    # test_raw_output_endpoint_requires_human_operator_and_reason's own
    # comment for why this drives it directly through the same container.
    executed = get_container().execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "COMPLETED"
    result_envelope_id = executed.result_envelope_id

    request_response = client.get(f"/internal/tool-gateway/v1/tool-requests/{tool_request_id}")
    result_response = client.get(f"/internal/tool-gateway/v1/tool-results/{result_envelope_id}")
    assert request_response.status_code == 200
    assert result_response.status_code == 200
    assert "vault://" not in request_response.text
    assert "vault://" not in result_response.text
