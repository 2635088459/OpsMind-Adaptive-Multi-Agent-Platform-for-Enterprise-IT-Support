"""SPEC-XOBS-001 Part C: tool-gateway is a consumer of agent-runtime's submit body and
the event-relay's approval-granted body, and a producer of the sync execute response.
Pinned against the shared `contracts/` fixtures.

The connector-manifest round-trip below is the regression guard for the
`side_effect_kind` bug: the field must survive `_connector_to_row_values` ->
`_row_to_connector` via `manifest_json.sideEffectKind`, never be re-derived from a
capability-name keyword heuristic.
"""

from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime
from pathlib import Path

import pytest

from tool_gateway.adapters.db.models import ToolConnectorRow
from tool_gateway.adapters.db.postgres_repositories import _connector_to_row_values, _row_to_connector
from tool_gateway.api.schemas import (
    ApprovalGrantedEventRequest,
    ExecuteToolRequestResponse,
    SubmitToolRequestRequest,
)
from tool_gateway.domain.connector import Capability, ToolConnector
from tool_gateway.domain.enums import RiskLevel, SideEffectKind
from tool_gateway.domain.ids import ConnectorId
from tool_gateway.domain.values import NetworkPolicy, RetryPolicy, TimeoutPolicy

CONTRACTS = Path(__file__).resolve().parents[4] / "contracts"


def _fixture(name: str) -> dict:
    path = CONTRACTS / name
    assert path.is_file(), f"missing contract fixture: {path}"
    return json.loads(path.read_text())


def test_submit_tool_request_schema_accepts_the_agent_runtime_body():
    fixture = _fixture("tool-gateway-submit-tool-request.json")
    model = SubmitToolRequestRequest(**fixture)
    assert model.requested_by_type == "AGENT"
    assert isinstance(model.input_payload, dict)          # never a string
    assert model.capability_name == fixture["capability_name"]
    assert model.workflow_instance_id == fixture["workflow_instance_id"]


def test_execute_tool_request_response_schema_matches_the_fixture():
    fixture = _fixture("tool-gateway-execute-tool-request-response.json")
    model = ExecuteToolRequestResponse(**fixture)
    assert model.status == "COMPLETED"
    assert model.tool_request_id == fixture["tool_request_id"]
    assert isinstance(model.output, dict)


def test_approval_granted_event_request_accepts_the_relay_body():
    fixture = _fixture("tool-gateway-approval-granted-event-request.json")
    model = ApprovalGrantedEventRequest(**fixture)
    assert model.approved_by
    assert model.tool_request_id == fixture["tool_request_id"]
    assert isinstance(model.constraints, dict)


def test_connector_side_effect_kind_survives_a_manifest_round_trip():
    connector = ToolConnector.register(
        connector_id=ConnectorId.new_id(), name="keycloak-identity-reset-link", version="1.0.0",
        capabilities=(Capability("identity.user.sendPasswordResetLink"),),
        input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1",
        risk_level=RiskLevel.MEDIUM, requires_approval=False,
        # A MUTATING capability whose NAME contains none of the legacy heuristic
        # keywords (create/update/delete/...): before the fix this round-tripped to
        # READ_ONLY and every real execution failed UNSUPPORTED_CAPABILITY.
        side_effect_kind=SideEffectKind.MUTATING,
        secret_requirements=(), network_policy=NetworkPolicy(allowed_hosts=()),
        timeout_policy=TimeoutPolicy(connect_timeout_seconds=5, invoke_timeout_seconds=30),
        retry_policy=RetryPolicy(max_attempts=3, backoff_seconds=5),
    )

    values = _connector_to_row_values(connector)
    assert values["manifest_json"]["sideEffectKind"] == "MUTATING"

    row = ToolConnectorRow(
        id=connector.connector_id.value, created_at=datetime.now(UTC), **values,
    )
    rehydrated = _row_to_connector(row)
    assert rehydrated.side_effect_kind is SideEffectKind.MUTATING

    # And a legacy row with no sideEffectKind still falls back to the heuristic.
    legacy = ToolConnectorRow(id=uuid.uuid4(), created_at=datetime.now(UTC), **{**values, "manifest_json": {
        "allowedRequesterTypes": values["manifest_json"]["allowedRequesterTypes"],
        "consecutiveHealthCheckFailures": 0,
    }})
    assert _row_to_connector(legacy).side_effect_kind is SideEffectKind.READ_ONLY
