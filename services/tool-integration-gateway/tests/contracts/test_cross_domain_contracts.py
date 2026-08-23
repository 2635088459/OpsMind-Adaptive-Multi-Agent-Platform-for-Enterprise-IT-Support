"""14-testing-strategy §"Cross-Domain Contract Tests" — phase-06 SPEC-TG-022~025.
Asserts the literal wire shape of every published/consumed event against each
sibling domain's own frozen expectations, without a live integration to any of
them (none has a real HTTP/consumer wire-up to Tool Gateway yet — see phase-06
traceability entries for the full reasoning per spec):

- SPEC-TG-022 (03 Agent Runtime): ``tool.completed.v1``'s payload against
  ``03-agent-runtime-orchestration``'s own consumed-event field list
  (``docs/low-level-design/domains/03-agent-runtime-orchestration/06-event-
  contracts``) — ``gatewayCorrelationId``/``resultPayload`` there map onto this
  domain's own envelope ``correlationId``/{``summary``,``structuredOutput``}
  respectively (documented field-naming reconciliation, not a literal
  string match).
- SPEC-TG-023 (06 Policy Approval): no real domain-06 service/LLD exists yet
  (only a README placeholder) — asserts TG's own 06-event-contracts shapes are
  internally complete instead.
- SPEC-TG-024 (04 Memory Knowledge): ``evidenceRefs`` is populated, and no
  event payload ever carries raw content (INV-TG-007) — Memory Knowledge
  itself consumes tool evidence indirectly via Runtime's own
  ``workflow.completed.v1``, not directly from Tool Gateway (see
  ``docs/low-level-design/domains/04-memory-knowledge/06-event-contracts``),
  so this only verifies TG's own side of that indirect chain.
- SPEC-TG-025 (02 Ticket Workflow): ``ticketId``/``ticketCycleId`` propagate
  through every published event that names them. Ticket Workflow's own
  ``tool.execution.completed/failed/result-unknown.v1`` consumers (a
  completely different vocabulary — ``workflowId``/``actionId``/
  ``authorizationReference``) are treated as legacy scaffolding predating
  Agent Runtime Orchestration's introduction as the mediating domain — not
  bridged here; see this file's own traceability entry / memory for the full
  reasoning behind that call.
"""

from __future__ import annotations

import json
import uuid

import pytest

from tool_gateway.adapters.connectors.builtin.fake_connector import FakeConnectorAdapter
from tool_gateway.application.commands import (
    CreateToolRequestCommand,
    EvaluateToolRequestCommand,
    ExecuteToolRequestCommand,
    RegisterConnectorCommand,
)
from tool_gateway.container import Container
from tool_gateway.domain.connector import Capability, ToolConnector
from tool_gateway.domain.enums import ResultStatus, RiskLevel, SideEffectKind
from tool_gateway.domain.ids import ConnectorId
from tool_gateway.domain.values import ExecutionOutcome, NetworkPolicy, RetryPolicy, TimeoutPolicy
from tool_gateway.settings import Settings


@pytest.fixture()
def container() -> Container:
    return Container(settings=Settings(tool_gateway_persistence="memory"))


def _register_connector(container: Container, capability: str) -> None:
    container.register_connector_service.register_connector(RegisterConnectorCommand(
        name=f"connector-for-{capability}", version="1.0.0", capability_names=(capability,),
        input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1", risk_level="LOW",
        requires_approval=False, is_mutating=False, correlation_id=str(uuid.uuid4()),
    ))


def _register_connector_with_outcome(container: Container, capability: str, outcome: ExecutionOutcome) -> None:
    connector = ToolConnector.register(
        connector_id=ConnectorId.new_id(), name=f"fixed-outcome-{capability}", version="1.0.0",
        capabilities=(Capability(capability),), input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1",
        risk_level=RiskLevel.LOW, requires_approval=False, side_effect_kind=SideEffectKind.READ_ONLY,
        secret_requirements=(), network_policy=NetworkPolicy(allowed_hosts=()),
        timeout_policy=TimeoutPolicy(connect_timeout_seconds=5, invoke_timeout_seconds=30),
        retry_policy=RetryPolicy(max_attempts=3, backoff_seconds=5),
    )
    container.connector_registry_port.register(connector, FakeConnectorAdapter(outcome))


def _submit_and_complete(
    container: Container, capability: str, idempotency_key: str, ticket_id: str | None = None,
    ticket_cycle_id: str | None = None, workflow_instance_id: str | None = None, agent_task_id: str | None = None,
) -> tuple[str, dict]:
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key=idempotency_key, requested_by_type="AGENT", requested_by_id="agent-1", capability_name=capability,
        input_payload={}, reason="investigate", correlation_id=str(uuid.uuid4()), ticket_id=ticket_id,
        ticket_cycle_id=ticket_cycle_id, workflow_instance_id=workflow_instance_id, agent_task_id=agent_task_id,
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=created.tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    [event] = [
        e for e in container.outbox_repository.find_dispatchable(container.clock.now(), 100)
        if e.event_type == "tool.completed.v1" and e.payload["toolRequestId"] == created.tool_request_id
    ]
    return created.tool_request_id, event.payload


# --------------------------------------------------------------------------------
# SPEC-TG-022: 03 Agent Runtime Tool Contract
# --------------------------------------------------------------------------------

# 03-agent-runtime-orchestration 06-event-contracts §"tool.completed.v1" §"Key
# fields": toolRequestId, gatewayCorrelationId, workflowInstanceId,
# agentTaskId, status, resultPayload, occurredAt. gatewayCorrelationId and
# resultPayload map onto this domain's own envelope/payload shape — see this
# module's own docstring.
_AGENT_RUNTIME_DIRECT_FIELDS = ("toolRequestId", "workflowInstanceId", "agentTaskId", "status")


def test_tool_completed_v1_satisfies_agent_runtime_consumed_field_list(container: Container) -> None:
    _register_connector(container, "kubernetes.agentRuntimeContractOp")
    _, payload = _submit_and_complete(container, "kubernetes.agentRuntimeContractOp", "idem-aro-contract-1")

    for field in _AGENT_RUNTIME_DIRECT_FIELDS:
        assert field in payload, field
    # gatewayCorrelationId -> envelope.correlationId (asserted at the OutboxRecord level, not payload).
    # resultPayload -> {summary, structuredOutput} (both present).
    assert "summary" in payload and "structuredOutput" in payload
    assert payload["status"] == "SUCCEEDED"


def test_tool_completed_v1_envelope_carries_a_correlation_id(container: Container) -> None:
    """The envelope-level field 03-agent-runtime-orchestration's own contract
    calls ``gatewayCorrelationId``.
    """

    _register_connector(container, "kubernetes.agentRuntimeCorrelationOp")
    correlation_id = str(uuid.uuid4())
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-aro-contract-2", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.agentRuntimeCorrelationOp", input_payload={}, reason="investigate",
        correlation_id=correlation_id,
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=correlation_id,
    ))
    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=created.tool_request_id, lease_owner="worker-1", correlation_id=correlation_id,
    ))
    [event] = [
        e for e in container.outbox_repository.find_dispatchable(container.clock.now(), 100)
        if e.event_type == "tool.completed.v1" and e.payload["toolRequestId"] == created.tool_request_id
    ]
    assert event.correlation_id == correlation_id


# --------------------------------------------------------------------------------
# SPEC-TG-023: 06 Policy Approval Contract
# --------------------------------------------------------------------------------

def test_tool_approval_required_v1_shape_is_complete(container: Container) -> None:
    """No real domain-06 service/LLD exists yet — verified against this
    domain's own frozen 06-event-contracts shape instead of a live consumer.
    """

    container.register_connector_service.register_connector(RegisterConnectorCommand(
        name="approval-contract-connector", version="1.0.0", capability_names=("kubernetes.restartApprovalContractOp",),
        input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1", risk_level="HIGH",
        requires_approval=True, is_mutating=True, correlation_id=str(uuid.uuid4()),
    ))
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-approval-contract-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.restartApprovalContractOp", input_payload={}, reason="investigate",
        correlation_id=str(uuid.uuid4()), ticket_id=str(uuid.uuid4()), workflow_instance_id=str(uuid.uuid4()),
        agent_task_id=str(uuid.uuid4()),
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))

    [event] = [
        e for e in container.outbox_repository.find_dispatchable(container.clock.now(), 100)
        if e.event_type == "tool.approval.required.v1" and e.payload["toolRequestId"] == created.tool_request_id
    ]
    for field in (
        "toolRequestId", "approvalRequestId", "capabilityName", "riskLevel", "ticketId", "workflowInstanceId",
        "agentTaskId", "reason",
    ):
        assert field in event.payload, field


# --------------------------------------------------------------------------------
# SPEC-TG-024: 04 Memory Evidence Contract
# --------------------------------------------------------------------------------

def test_evidence_refs_populated_and_raw_content_never_appears_in_any_outbox_payload(container: Container) -> None:
    """04-memory-knowledge §"Consumed Events": "obtain ... tool evidence
    refs" (indirectly, via Runtime's own workflow.completed.v1 — see this
    module's own docstring). INV-TG-007: raw output content must never enter
    an event payload, only a controlled reference.
    """

    marker = "SECRET-RAW-CONTENT-MUST-NEVER-LEAK-4821"
    outcome = ExecutionOutcome(
        status=ResultStatus.SUCCESS, summary="ok", structured_output={}, raw_output=marker, error_code=None, retryable=False,
    )
    _register_connector_with_outcome(container, "kubernetes.evidenceContractOp", outcome)
    tool_request_id, payload = _submit_and_complete(container, "kubernetes.evidenceContractOp", "idem-evidence-contract-1")

    assert payload["evidenceRefs"], "evidenceRefs must be populated when raw output exists"
    assert marker not in json.dumps(payload)

    # No event published for this tool request, of any type, may carry the marker.
    for event in container.outbox_repository.find_dispatchable(container.clock.now(), 100):
        if event.aggregate_id == tool_request_id:
            assert marker not in json.dumps(event.payload)


# --------------------------------------------------------------------------------
# SPEC-TG-025: 02 Ticket Workflow Traceability Contract
# --------------------------------------------------------------------------------

def test_ticket_traceability_fields_propagate_through_tool_completed_v1(container: Container) -> None:
    """02-ticket-workflow's own real, built ``tool.execution.*`` consumers use
    an unrelated vocabulary predating Agent Runtime Orchestration (see this
    module's own docstring) — not bridged. What genuinely matters for
    traceability regardless of transport is that ticketId/ticketCycleId make
    it into the published fact at all.
    """

    _register_connector(container, "kubernetes.ticketTraceabilityOp")
    ticket_id, ticket_cycle_id = str(uuid.uuid4()), str(uuid.uuid4())
    _, payload = _submit_and_complete(
        container, "kubernetes.ticketTraceabilityOp", "idem-ticket-contract-1", ticket_id=ticket_id, ticket_cycle_id=ticket_cycle_id,
    )
    assert payload["ticketId"] == ticket_id
    assert payload["ticketCycleId"] == ticket_cycle_id


def test_ticket_traceability_fields_propagate_through_rejected_event(container: Container) -> None:
    """SPEC-TG-025: a rejected fact with no ticket/workflow context would be
    untraceable to whatever raised it — closed alongside this contract suite
    (ticketId/ticketCycleId/workflowInstanceId/agentTaskId now match
    tool.completed.v1's own already-complete field set).
    """

    ticket_id, workflow_instance_id = str(uuid.uuid4()), str(uuid.uuid4())
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-ticket-contract-2", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="no.such.capability", input_payload={}, reason="try something", correlation_id=str(uuid.uuid4()),
        ticket_id=ticket_id, workflow_instance_id=workflow_instance_id,
    ))
    assert created.status == "REJECTED"

    [event] = [
        e for e in container.outbox_repository.find_dispatchable(container.clock.now(), 100)
        if e.event_type == "tool.request.rejected.v1" and e.payload["toolRequestId"] == created.tool_request_id
    ]
    assert event.payload["ticketId"] == ticket_id
    assert event.payload["workflowInstanceId"] == workflow_instance_id

    recent = container.audit_record_repository.find_recent(50)
    [audit_entry] = [a for a in recent if a.action == "request_rejected" and a.resource_id == created.tool_request_id]
    assert audit_entry.ticket_id == ticket_id
