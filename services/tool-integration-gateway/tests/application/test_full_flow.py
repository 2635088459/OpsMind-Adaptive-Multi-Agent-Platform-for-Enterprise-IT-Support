"""Application-layer walking-skeleton test: exercises the seven named
13-package-and-class-design services (plus register_connector) wired through
tool_gateway.container against in-memory adapters — no HTTP layer, mirrors
memory-knowledge-service's own application-level test suite convention.

Explicitly forces ``tool_gateway_persistence="memory"``: SPEC-TG-002 made
"postgres" the container's own default (settings.py's own docstring explains
why), so this fast/hermetic suite must opt out explicitly rather than silently
attempting a real database connection — mirrors memory-knowledge-service's own
tests/test_app.py precedent for the same situation.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from tool_gateway.adapters.connectors.builtin.fake_connector import FakeConnectorAdapter
from tool_gateway.application.commands import (
    CancelToolRequestCommand,
    ConsumeApprovalDecisionCommand,
    ConsumePolicyRuleChangedCommand,
    ConsumeWorkflowCancelledCommand,
    CreateToolRequestCommand,
    DispatchOutboxCommand,
    EvaluateToolRequestCommand,
    ExecuteToolRequestCommand,
    ReclaimExpiredLeasesCommand,
    ReconcileExecutionCommand,
    RecordApprovalDecisionCommand,
    RegisterConnectorCommand,
    UpdateConnectorStatusCommand,
)
from tool_gateway.application.exceptions import (
    ApprovalLinkageMismatchException,
    ConnectorNotFoundException,
    OutboxRecordNotDeadLetterException,
    OutboxRecordNotFoundException,
    RawOutputForbiddenException,
)
from tool_gateway.application.views import ConnectorView
from tool_gateway.container import Container
from tool_gateway.domain.connector import Capability, ToolConnector
from tool_gateway.domain.errors import InvalidToolRequestTransitionException
from tool_gateway.domain.enums import ResultStatus, RiskLevel, SideEffectKind, ToolExecutionStatus, ToolRequestStatus
from tool_gateway.domain.ids import ConnectorId, ToolExecutionId, ToolRequestId
from tool_gateway.domain.records import OutboxRecord
from tool_gateway.domain.tool_execution import ToolExecution
from tool_gateway.domain.values import ExecutionOutcome, NetworkPolicy, RetryPolicy, TimeoutPolicy
from tool_gateway.settings import Settings


def _register_connector_with_fixed_outcome(
    container: Container, capability: str, outcome: ExecutionOutcome, secret_requirements: tuple[str, ...] = (),
    max_attempts: int = 3, backoff_seconds: int = 5, reconcile_outcome: ExecutionOutcome | None = None,
) -> ConnectorId:
    """SPEC-TG-011 promoted this file's own former private test double
    (``_FixedOutcomeConnector``) into a real, reusable
    ``adapters.connectors.builtin.fake_connector.FakeConnectorAdapter`` — see
    that module's own docstring.
    """

    connector_id = ConnectorId.new_id()
    connector = ToolConnector.register(
        connector_id=connector_id, name=f"fixed-outcome-connector-{capability}", version="1.0.0",
        capabilities=(Capability(capability),), input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1",
        risk_level=RiskLevel.LOW, requires_approval=False, side_effect_kind=SideEffectKind.READ_ONLY,
        secret_requirements=secret_requirements, network_policy=NetworkPolicy(allowed_hosts=()),
        timeout_policy=TimeoutPolicy(connect_timeout_seconds=5, invoke_timeout_seconds=30),
        retry_policy=RetryPolicy(max_attempts=max_attempts, backoff_seconds=backoff_seconds),
    )
    container.connector_registry_port.register(connector, FakeConnectorAdapter(outcome, reconcile_outcome=reconcile_outcome))
    return connector_id


@pytest.fixture()
def container() -> Container:
    return Container(settings=Settings(tool_gateway_persistence="memory"))


def _register_connector(
    container: Container, capability: str, requires_approval: bool, is_mutating: bool, version: str = "1.0.0",
) -> ConnectorView:
    return container.register_connector_service.register_connector(RegisterConnectorCommand(
        name=f"connector-for-{capability}", version=version, capability_names=(capability,),
        input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1",
        risk_level="HIGH" if requires_approval else "LOW", requires_approval=requires_approval, is_mutating=is_mutating,
        correlation_id=str(uuid.uuid4()),
    ))


def test_low_risk_capability_auto_executes_to_completed(container: Container) -> None:
    """04-use-cases UC-TG-001 + UC-TG-002 end to end."""

    _register_connector(container, "kubernetes.getPodLogs", requires_approval=False, is_mutating=False)

    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.getPodLogs", input_payload={"pod": "app-1"}, reason="investigate crash loop",
        correlation_id=str(uuid.uuid4()),
    ))
    assert created.status == "VALIDATING"

    evaluated = container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    assert evaluated.status == "QUEUED"

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=created.tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "COMPLETED"
    assert executed.result_envelope_id is not None

    result = container.execute_tool_request_service.find_result(executed.result_envelope_id)
    assert result.status == "SUCCESS"
    assert result.raw_output_ref is None  # INV-TG-007: no raw content, only a reference (unset in this spec's scope)


def test_re_executing_an_already_completed_request_raises_instead_of_republishing(container: Container) -> None:
    """SPEC-TG-032 final coverage audit / 14-testing-strategy §"Cross-Domain
    Contract Tests": "duplicate tool.completed.v1 does not complete AgentTask
    twice." Agent Runtime's own consumer-side idempotency is out of this
    domain's own test scope (no live consumer exists — see SPEC-TG-022's own
    traceability entry), but Tool Gateway's own half of that guarantee — never
    *publishing* a second ``tool.completed.v1`` for the same finalized
    ToolRequest even if a worker double-claim race calls
    ``execute_tool_request`` again — is exactly what 03-state-machine's own
    ``COMPLETED -> {}`` transition table (no outbound edges) already
    structurally enforces. This proves it end to end rather than only at the
    domain-unit level (``tests/domain/test_tool_request.py`` already covers
    the bare transition-table rejection).
    """

    _register_connector(container, "kubernetes.doubleClaimGetLogs", requires_approval=False, is_mutating=False)
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-double-claim-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.doubleClaimGetLogs", input_payload={"pod": "app-1"}, reason="investigate",
        correlation_id=str(uuid.uuid4()),
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=created.tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "COMPLETED"

    with pytest.raises(InvalidToolRequestTransitionException):
        container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
            tool_request_id=created.tool_request_id, lease_owner="worker-2", correlation_id=str(uuid.uuid4()),
        ))

    completed_events = [e for e in _outbox_events(container, "tool.completed.v1") if e.payload["toolRequestId"] == created.tool_request_id]
    assert len(completed_events) == 1


def test_duplicate_idempotency_key_does_not_create_a_second_request(container: Container) -> None:
    """Reliability Acceptance: "Duplicate requests ... do not create duplicate
    external side effects."
    """

    _register_connector(container, "kubernetes.getPodLogs", requires_approval=False, is_mutating=False)
    command = CreateToolRequestCommand(
        idempotency_key="idem-shared", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.getPodLogs", input_payload={}, reason="investigate", correlation_id=str(uuid.uuid4()),
    )
    first = container.create_tool_request_service.create_tool_request(command)
    second = container.create_tool_request_service.create_tool_request(command)
    assert first.tool_request_id == second.tool_request_id


def test_unregistered_capability_is_rejected(container: Container) -> None:
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-2", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="unknown.capability", input_payload={}, reason="try something", correlation_id=str(uuid.uuid4()),
    ))
    assert created.status == "REJECTED"


def test_high_risk_capability_waits_for_approval_then_executes(container: Container) -> None:
    """04-use-cases UC-TG-003 end to end."""

    _register_connector(container, "kubernetes.restartDeployment", requires_approval=True, is_mutating=True)

    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-3", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.restartDeployment", input_payload={"deployment": "checkout"},
        reason="clear stuck pods", correlation_id=str(uuid.uuid4()),
    ))
    evaluated = container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    assert evaluated.status == "WAITING_APPROVAL"

    approved = container.approve_tool_request_service.record_approval_decision(RecordApprovalDecisionCommand(
        tool_request_id=created.tool_request_id, approved=True, decided_by="approver-1", correlation_id=str(uuid.uuid4()),
    ))
    assert approved.status == "QUEUED"

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=created.tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "COMPLETED"


def test_approval_denied_stops_the_request(container: Container) -> None:
    _register_connector(container, "kubernetes.deleteNamespace", requires_approval=True, is_mutating=True)

    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-4", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.deleteNamespace", input_payload={}, reason="cleanup", correlation_id=str(uuid.uuid4()),
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    denied = container.approve_tool_request_service.record_approval_decision(RecordApprovalDecisionCommand(
        tool_request_id=created.tool_request_id, approved=False, decided_by="approver-1", correlation_id=str(uuid.uuid4()),
        denial_reason="too destructive",
    ))
    assert denied.status == "APPROVAL_DENIED"
    assert denied.denial_reason == "too destructive"


def test_cancel_queued_request(container: Container) -> None:
    """04-use-cases UC-TG-006. Capability name deliberately avoids
    StaticPolicyAdapter's mutating-keyword list (see that adapter's own module
    docstring) so this request auto-approves straight to QUEUED.
    """

    _register_connector(container, "servicenow.openChangeRequest", requires_approval=False, is_mutating=True)
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-5", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="servicenow.openChangeRequest", input_payload={}, reason="open change",
        correlation_id=str(uuid.uuid4()),
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    cancelled = container.cancel_tool_request_service.cancel_tool_request(CancelToolRequestCommand(
        tool_request_id=created.tool_request_id, idempotency_key=f"cancel-{uuid.uuid4()}", requested_by="agent-1",
        reason="no longer needed", correlation_id=str(uuid.uuid4()),
    ))
    assert cancelled.status == "CANCELLED"


def test_outbox_dispatch_publishes_accepted_event(container: Container) -> None:
    """00-implementation-roadmap §"Closure Principles": "Every published event
    must go through Gateway outbox."
    """

    _register_connector(container, "slack.notifyChannel", requires_approval=False, is_mutating=True)
    container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-6", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="slack.notifyChannel", input_payload={}, reason="notify oncall", correlation_id=str(uuid.uuid4()),
    ))
    published = container.publish_outbox_service.dispatch(DispatchOutboxCommand(batch_size=10))
    assert published >= 1


def test_repeated_cancel_on_an_already_cancelled_request_is_idempotent(container: Container) -> None:
    """05-api-contracts: cancel "Requires idempotencyKey and requester" — a
    repeat call is a no-op, not an INVALID_STATE_TRANSITION conflict.
    """

    _register_connector(container, "servicenow.openChangeRequestTwice", requires_approval=False, is_mutating=True)
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-cancel-twice", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="servicenow.openChangeRequestTwice", input_payload={}, reason="open change",
        correlation_id=str(uuid.uuid4()),
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    command = CancelToolRequestCommand(
        tool_request_id=created.tool_request_id, idempotency_key="cancel-key-1", requested_by="agent-1",
        reason="no longer needed", correlation_id=str(uuid.uuid4()),
    )
    first = container.cancel_tool_request_service.cancel_tool_request(command)
    second = container.cancel_tool_request_service.cancel_tool_request(command)
    assert first.status == "CANCELLED"
    assert second.status == "CANCELLED"


def test_create_tool_request_binds_the_resolved_connector(container: Container) -> None:
    """02-business-invariants INV-TG-008: "Tool Request records the schema
    version used at submission time."
    """

    registered = _register_connector(container, "kubernetes.describePod", requires_approval=False, is_mutating=False)
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-bind-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.describePod", input_payload={}, reason="inspect pod", correlation_id=str(uuid.uuid4()),
    ))
    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(created.tool_request_id)))
    assert stored is not None
    assert str(stored.resolved_connector_id) == registered.connector_id
    assert stored.resolved_connector_version == "1.0.0"


def test_execute_terminal_fails_when_the_bound_connector_was_disabled_after_accept_with_no_fallback(container: Container) -> None:
    """SPEC-TG-030 "Crash Recovery Backpressure Scaling" 10-failure-handling
    §"Connector Crash Or Unavailability": "Queued requests need connector
    reselection or terminal failure." No other connector implements this
    capability, so there is nothing to reselect onto — the request must reach
    TERMINAL_FAILED, publish a final ``tool.completed.v1``, and leave an audit
    trail, never sit QUEUED forever silently re-failing on every worker poll
    (the exact gap this spec closes — before it, this raised
    ``CapabilityNotRegisteredException``, which ``ExecutionWorker.run_once()``
    only logs and swallows).
    """

    registered = _register_connector(container, "kubernetes.describePodTwice", requires_approval=False, is_mutating=False)
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-bind-2", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.describePodTwice", input_payload={}, reason="inspect pod", correlation_id=str(uuid.uuid4()),
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))

    container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=registered.connector_id, action="DISABLE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))

    result = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=created.tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert result.status == "TERMINAL_FAILED"

    [event] = [e for e in _outbox_events(container, "tool.completed.v1") if e.payload["toolRequestId"] == created.tool_request_id]
    assert event.payload["status"] == "TERMINAL_FAILED"


def test_execute_reselects_a_fallback_connector_when_the_bound_read_only_connector_is_unavailable(container: Container) -> None:
    """SPEC-TG-030 same section: a READ_ONLY capability with a genuinely
    eligible fallback connector reselects onto it and completes normally,
    instead of terminal-failing a request a fallback could have served.
    """

    disabled = _register_connector(container, "kubernetes.describePodFallback", requires_approval=False, is_mutating=False)
    fallback = _register_connector(
        container, "kubernetes.describePodFallback", requires_approval=False, is_mutating=False, version="2.0.0",
    )
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-bind-fallback", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.describePodFallback", input_payload={}, reason="inspect pod", correlation_id=str(uuid.uuid4()),
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(created.tool_request_id)))
    assert stored is not None and str(stored.resolved_connector_id) == disabled.connector_id

    container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=disabled.connector_id, action="DISABLE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))

    result = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=created.tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert result.status == "COMPLETED"

    reselected = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(created.tool_request_id)))
    assert reselected is not None
    assert str(reselected.resolved_connector_id) == fallback.connector_id

    [audit] = [
        a for a in container.audit_record_repository.find_recent(100)
        if a.action == "connector_reselected" and a.resource_id == created.tool_request_id
    ]
    assert audit.connector_id == fallback.connector_id


def test_execute_never_reselects_a_fallback_connector_for_a_mutating_capability(container: Container) -> None:
    """SPEC-TG-030 same section: "High-risk mutation must not switch
    connectors automatically unless policy allows it." No grounded
    ``PolicyPort`` hook exists for that permission anywhere in this domain, so
    a MUTATING capability's bound connector going unavailable always
    terminal-fails, even when another connector implementing the same
    capability is registered and eligible.
    """

    disabled = _register_connector(container, "servicenow.openChangeRequestFallback", requires_approval=False, is_mutating=True)
    _register_connector(
        container, "servicenow.openChangeRequestFallback", requires_approval=False, is_mutating=True, version="2.0.0",
    )
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-bind-mutating-fallback", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="servicenow.openChangeRequestFallback", input_payload={}, reason="restart", correlation_id=str(uuid.uuid4()),
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))

    container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=disabled.connector_id, action="DISABLE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))

    result = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=created.tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert result.status == "TERMINAL_FAILED"


def test_update_connector_status_enable_disable_deprecate(container: Container) -> None:
    """05-api-contracts §"Connector Admin API": ``PATCH /connectors/
    {connectorId}/status`` — "Enable, disable, or deprecate a connector."
    """

    registered = _register_connector(container, "kubernetes.tailLogs", requires_approval=False, is_mutating=False)

    disabled = container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=registered.connector_id, action="DISABLE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))
    assert disabled.health_status == "DISABLED"

    enabled = container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=registered.connector_id, action="ENABLE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))
    assert enabled.health_status == "ACTIVE"

    deprecated = container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=registered.connector_id, action="DEPRECATE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))
    assert deprecated.health_status == "DEPRECATED"


def test_list_capabilities_returns_only_active_schedulable_connectors(container: Container) -> None:
    """05-api-contracts §"Connector Admin API": ``GET /capabilities`` — "Return
    capability registry visible to Runtime."
    """

    active = _register_connector(container, "slack.postMessage", requires_approval=False, is_mutating=True)
    disabled = _register_connector(container, "slack.deleteMessage", requires_approval=True, is_mutating=True)
    container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=disabled.connector_id, action="DISABLE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))

    capabilities = {view.capability_name: view for view in container.register_connector_service.list_capabilities()}
    assert "slack.postMessage" in capabilities
    assert "slack.deleteMessage" not in capabilities
    assert capabilities["slack.postMessage"].connector_count == 1
    assert active.connector_id  # sanity: the fixture actually registered something


def _submit_and_queue(container: Container, capability: str, idempotency_key: str) -> str:
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key=idempotency_key, requested_by_type="AGENT", requested_by_id="agent-1", capability_name=capability,
        input_payload={}, reason="investigate", correlation_id=str(uuid.uuid4()),
    ))
    evaluated = container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    assert evaluated.status == "QUEUED"
    return created.tool_request_id


def test_execute_timeout_outcome_stays_distinct_and_leaves_request_executing(container: Container) -> None:
    """02-business-invariants INV-TG-010: "Connector timeout ... must remain
    distinguishable. [It] must not be collapsed into a generic failure."
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.TIMED_OUT, summary="connector timed out", structured_output={}, raw_output=None,
        error_code="TIMEOUT", retryable=True,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.slowOp", outcome)
    tool_request_id = _submit_and_queue(container, "kubernetes.slowOp", "idem-timeout-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    # 03-state-machine: no ToolRequest state names TIMED_OUT — the fact lives on
    # the ToolExecution attempt; the request itself stays EXECUTING pending
    # reconciliation (04-use-cases UC-TG-005), not silently marked FAILED.
    assert executed.status == "EXECUTING"

    attempts = container.tool_execution_repository.find_attempts(ToolRequestId(uuid.UUID(tool_request_id)))
    assert [a.status.name for a in attempts] == ["TIMED_OUT"]


def test_execute_partial_side_effect_outcome_stays_distinct(container: Container) -> None:
    """INV-TG-010: "partial side effect must remain distinguishable.\""""

    outcome = ExecutionOutcome(
        status=ResultStatus.PARTIAL_SIDE_EFFECT, summary="uncertain whether the mutation applied", structured_output={},
        raw_output=None, error_code=None, retryable=False,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.uncertainOp", outcome)
    tool_request_id = _submit_and_queue(container, "kubernetes.uncertainOp", "idem-partial-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "EXECUTING"

    attempts = container.tool_execution_repository.find_attempts(ToolRequestId(uuid.UUID(tool_request_id)))
    assert [a.status.name for a in attempts] == ["PARTIAL_SIDE_EFFECT"]


def test_execute_retryable_failure_with_attempts_remaining_reschedules_to_queued(container: Container) -> None:
    """SPEC-TG-016 UC-TG-004 step 3: "Gateway creates the next attempt based on
    retry policy." INV-TG-010: a retryable connector failure must remain
    distinguishable from a terminal one — this asserts it re-enters QUEUED
    (retry-eligible) rather than being silently collapsed into a dead end.
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.FAILED, summary="connector returned an error", structured_output={}, raw_output=None,
        error_code="UPSTREAM_5XX", retryable=True,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.failingOp", outcome, max_attempts=3, backoff_seconds=30)
    tool_request_id = _submit_and_queue(container, "kubernetes.failingOp", "idem-failed-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "QUEUED"

    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(tool_request_id)))
    assert stored is not None
    assert stored.status is ToolRequestStatus.QUEUED
    assert stored.retry_not_before is not None

    attempts = container.tool_execution_repository.find_attempts(ToolRequestId(uuid.UUID(tool_request_id)))
    assert [a.status.name for a in attempts] == ["RETRY_SCHEDULED"]
    assert attempts[0].error_code == "UPSTREAM_5XX"
    assert attempts[0].retryable is True

    [event] = [e for e in _outbox_events(container, "tool.execution.retry_scheduled.v1") if e.payload["toolRequestId"] == tool_request_id]
    assert event.payload["attemptNumber"] == 1
    assert event.payload["errorCode"] == "UPSTREAM_5XX"

    # SPEC-TG-016: a retried request must not be immediately re-claimable
    # before its backoff elapses.
    still_backing_off = container.tool_request_repository.find_queued(container.clock.now(), 10)
    assert not any(r.tool_request_id == stored.tool_request_id for r in still_backing_off)


def test_execute_retryable_failure_exhausting_max_attempts_reaches_terminal_failed(container: Container) -> None:
    """SPEC-TG-016 UC-TG-004 step 4: "If max attempts are reached, ToolRequest
    enters TERMINAL_FAILED." Publishes a final tool.completed.v1 carrying the
    real failed attempt's own connector/errorCode context.
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.FAILED, summary="connector returned an error", structured_output={}, raw_output=None,
        error_code="PERMISSION_DENIED", retryable=True,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.exhaustedOp", outcome, max_attempts=1)
    tool_request_id = _submit_and_queue(container, "kubernetes.exhaustedOp", "idem-exhausted-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "TERMINAL_FAILED"

    [event] = [e for e in _outbox_events(container, "tool.completed.v1") if e.payload["toolRequestId"] == tool_request_id]
    assert event.payload["status"] == "TERMINAL_FAILED"
    assert event.payload["errorCode"] == "PERMISSION_DENIED"
    assert event.payload["executionId"] is not None


def test_execute_non_retryable_failure_reaches_terminal_failed_on_first_attempt(container: Container) -> None:
    """SPEC-TG-016: ``ExecutionOutcome.retryable=False`` (e.g. a permission
    error) must not be retried even with attempts remaining — the connector's
    own per-outcome classification overrides the bare attempts-remaining count.
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.FAILED, summary="not authorized", structured_output={}, raw_output=None,
        error_code="FORBIDDEN", retryable=False,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.forbiddenOp", outcome, max_attempts=5)
    tool_request_id = _submit_and_queue(container, "kubernetes.forbiddenOp", "idem-forbidden-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "TERMINAL_FAILED"


def _outbox_events(container: Container, event_type: str) -> list:
    return [r for r in container.outbox_repository.find_dispatchable(datetime.now(UTC), 100) if r.event_type == event_type]


def test_policy_denied_capability_is_denied_outright_and_publishes_completed_event(container: Container) -> None:
    """SPEC-TG-007 10-failure-handling §"Policy / Approval Failure": "Policy
    denied ... Gateway publishes final tool.completed.v1 with status
    POLICY_DENIED."
    """

    _register_connector(container, "kubernetes.wipeCluster", requires_approval=False, is_mutating=True)
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-policy-denied-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.wipeCluster", input_payload={}, reason="clean slate", correlation_id=str(uuid.uuid4()),
    ))
    evaluated = container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    assert evaluated.status == "POLICY_DENIED"

    completed_events = _outbox_events(container, "tool.completed.v1")
    [event] = [e for e in completed_events if e.payload["toolRequestId"] == created.tool_request_id]
    assert event.payload["status"] == "POLICY_DENIED"
    assert event.payload["executionId"] is None


def test_create_tool_request_rejection_publishes_rejected_event(container: Container) -> None:
    """06-event-contracts §"tool.request.rejected.v1": "Published when request
    is rejected due to schema, capability, permission, or idempotency
    conflict."
    """

    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-rejected-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="no.such.capability", input_payload={}, reason="try something", correlation_id=str(uuid.uuid4()),
    ))
    assert created.status == "REJECTED"

    rejected_events = _outbox_events(container, "tool.request.rejected.v1")
    assert any(e.payload["toolRequestId"] == created.tool_request_id for e in rejected_events)


def test_approval_required_publishes_event(container: Container) -> None:
    """SPEC-TG-008 06-event-contracts §"tool.approval.required.v1": "Published
    when approval is required, so domain 06 can create or link an approval
    request."
    """

    _register_connector(container, "kubernetes.restartDeploymentApproval", requires_approval=True, is_mutating=True)
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-approval-required-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.restartDeploymentApproval", input_payload={}, reason="clear stuck pods",
        correlation_id=str(uuid.uuid4()),
    ))
    evaluated = container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    assert evaluated.status == "WAITING_APPROVAL"
    assert evaluated.approval_request_id is not None

    [event] = [e for e in _outbox_events(container, "tool.approval.required.v1") if e.payload["toolRequestId"] == created.tool_request_id]
    assert event.payload["approvalRequestId"] == evaluated.approval_request_id
    assert event.payload["riskLevel"] == "HIGH"


def _submit_waiting_approval(container: Container, capability: str, idempotency_key: str) -> str:
    _register_connector(container, capability, requires_approval=True, is_mutating=True)
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key=idempotency_key, requested_by_type="AGENT", requested_by_id="agent-1", capability_name=capability,
        input_payload={}, reason="needs approval", correlation_id=str(uuid.uuid4()),
    ))
    evaluated = container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    assert evaluated.status == "WAITING_APPROVAL"
    return created.tool_request_id


def test_consume_approval_decision_grants_and_queues(container: Container) -> None:
    """SPEC-TG-009 06-event-contracts §"approval.granted.v1": "Only a Tool
    Request in WAITING_APPROVAL with valid approval linkage may move to
    QUEUED."
    """

    tool_request_id = _submit_waiting_approval(container, "kubernetes.restartConsume1", "idem-consume-1")
    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(tool_request_id)))
    approval_request_id = str(stored.approval_ref.approval_request_id)

    result = container.approve_tool_request_service.consume_approval_decision(ConsumeApprovalDecisionCommand(
        event_id=f"evt-{uuid.uuid4()}", tool_request_id=tool_request_id, approval_request_id=approval_request_id,
        approved=True, decided_by="approver-1", correlation_id=str(uuid.uuid4()),
    ))
    assert result.status == "QUEUED"


def test_consume_approval_decision_denied_publishes_completed_event(container: Container) -> None:
    tool_request_id = _submit_waiting_approval(container, "kubernetes.restartConsume2", "idem-consume-2")
    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(tool_request_id)))
    approval_request_id = str(stored.approval_ref.approval_request_id)

    result = container.approve_tool_request_service.consume_approval_decision(ConsumeApprovalDecisionCommand(
        event_id=f"evt-{uuid.uuid4()}", tool_request_id=tool_request_id, approval_request_id=approval_request_id,
        approved=False, decided_by="approver-1", correlation_id=str(uuid.uuid4()), denial_reason="too risky",
    ))
    assert result.status == "APPROVAL_DENIED"

    [event] = [e for e in _outbox_events(container, "tool.completed.v1") if e.payload["toolRequestId"] == tool_request_id]
    assert event.payload["status"] == "APPROVAL_DENIED"


def test_consume_approval_decision_deduplicates_by_event_id(container: Container) -> None:
    """09-concurrency-and-idempotency §"Outbox Idempotency" / 08-transaction-
    and-outbox §"Processed Events": redelivering the same eventId must not
    reapply the decision.
    """

    tool_request_id = _submit_waiting_approval(container, "kubernetes.restartConsume3", "idem-consume-3")
    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(tool_request_id)))
    approval_request_id = str(stored.approval_ref.approval_request_id)
    event_id = f"evt-{uuid.uuid4()}"
    command = ConsumeApprovalDecisionCommand(
        event_id=event_id, tool_request_id=tool_request_id, approval_request_id=approval_request_id, approved=True,
        decided_by="approver-1", correlation_id=str(uuid.uuid4()),
    )

    first = container.approve_tool_request_service.consume_approval_decision(command)
    second = container.approve_tool_request_service.consume_approval_decision(command)
    assert first.status == "QUEUED"
    assert second.status == "QUEUED"


def test_consume_approval_decision_skips_when_already_resolved(container: Container) -> None:
    """09-concurrency-and-idempotency §"Approval Event Idempotency": "If
    ToolRequest is already QUEUED, EXECUTING, or final, skip."
    """

    tool_request_id = _submit_waiting_approval(container, "kubernetes.restartConsume4", "idem-consume-4")
    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(tool_request_id)))
    approval_request_id = str(stored.approval_ref.approval_request_id)

    container.approve_tool_request_service.consume_approval_decision(ConsumeApprovalDecisionCommand(
        event_id=f"evt-{uuid.uuid4()}", tool_request_id=tool_request_id, approval_request_id=approval_request_id,
        approved=True, decided_by="approver-1", correlation_id=str(uuid.uuid4()),
    ))

    # A second, distinct event (e.g. a duplicate approval.granted.v1 the
    # broker redelivered under a new eventId) arrives after the request is
    # already QUEUED — must not raise, must not re-transition.
    replay = container.approve_tool_request_service.consume_approval_decision(ConsumeApprovalDecisionCommand(
        event_id=f"evt-{uuid.uuid4()}", tool_request_id=tool_request_id, approval_request_id=approval_request_id,
        approved=True, decided_by="approver-1", correlation_id=str(uuid.uuid4()),
    ))
    assert replay.status == "QUEUED"


def test_consume_approval_decision_rejects_linkage_mismatch(container: Container) -> None:
    """09-concurrency-and-idempotency §"Approval Event Idempotency": "If
    approval linkage does not match, write security audit and reject."
    """

    tool_request_id = _submit_waiting_approval(container, "kubernetes.restartConsume5", "idem-consume-5")

    with pytest.raises(ApprovalLinkageMismatchException):
        container.approve_tool_request_service.consume_approval_decision(ConsumeApprovalDecisionCommand(
            event_id=f"evt-{uuid.uuid4()}", tool_request_id=tool_request_id, approval_request_id=str(uuid.uuid4()),
            approved=True, decided_by="approver-1", correlation_id=str(uuid.uuid4()),
        ))

    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(tool_request_id)))
    assert stored is not None
    assert stored.status is ToolRequestStatus.WAITING_APPROVAL


def test_consume_policy_rule_changed_deduplicates_by_event_id(container: Container) -> None:
    event_id = f"evt-{uuid.uuid4()}"
    command = ConsumePolicyRuleChangedCommand(event_id=event_id, rule_id="rule-1", correlation_id=str(uuid.uuid4()))

    first = container.consume_policy_rule_changed_service.consume_policy_rule_changed(command)
    second = container.consume_policy_rule_changed_service.consume_policy_rule_changed(command)
    assert first is True
    assert second is False


def test_execute_mutating_connector_uses_full_operation_key_format(container: Container) -> None:
    """SPEC-TG-013 09-concurrency-and-idempotency §"Connector Operation Key":
    "Recommended operationKey format: toolRequestId:attemptNumber:connectorId:
    capabilityName." Registered directly through ``register_connector_service``
    (not ``_register_connector_with_fixed_outcome``) so the attempt runs
    against the real ``EchoConnectorAdapter`` and reaches COMPLETED.
    """

    registered = _register_connector(container, "kubernetes.patchDeploymentOpKey", requires_approval=False, is_mutating=True)
    tool_request_id = _submit_and_queue(container, "kubernetes.patchDeploymentOpKey", "idem-opkey-1")

    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))

    [attempt] = container.tool_execution_repository.find_attempts(ToolRequestId(uuid.UUID(tool_request_id)))
    expected_key = f"{tool_request_id}:1:{registered.connector_id}:kubernetes.patchDeploymentOpKey"
    assert str(attempt.operation_key) == expected_key


def test_execute_redacts_secrets_nested_inside_structured_output(container: Container) -> None:
    """SPEC-TG-014 11-security §"Output Redaction": a connector's
    ``structured_output`` is untrusted free-form JSON — a secret-shaped string
    nested inside it (not just the top-level ``summary``) must still be
    redacted before it reaches the API/event.
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.SUCCESS, summary="ok", structured_output={
            "detail": {"note": "found api_key: AKIAABCDEFGHIJKLMNOP in config"}, "items": ["contact admin@example.com"],
        },
        raw_output=None, error_code=None, retryable=False,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.leakyOp", outcome)
    tool_request_id = _submit_and_queue(container, "kubernetes.leakyOp", "idem-redact-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    result = container.execute_tool_request_service.find_result(executed.result_envelope_id)
    assert "AKIAABCDEFGHIJKLMNOP" not in str(result.structured_output)
    assert "admin@example.com" not in str(result.structured_output)
    assert result.structured_output["detail"]["note"] == "found [REDACTED] in config"
    assert result.redaction_status == "REDACTED"


def test_tool_completed_event_carries_full_06_event_contracts_field_set(container: Container) -> None:
    """SPEC-TG-015 06-event-contracts §"tool.completed.v1": the published event
    must carry every contractual field, not just the original SPEC-TG-001
    subset (toolRequestId/executionId/status/summary).
    """

    _register_connector(container, "kubernetes.fullPayloadOp", requires_approval=False, is_mutating=False)
    tool_request_id = _submit_and_queue(container, "kubernetes.fullPayloadOp", "idem-payload-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    [event] = [e for e in _outbox_events(container, "tool.completed.v1") if e.payload["toolRequestId"] == tool_request_id]
    payload = event.payload
    for field in (
        "toolRequestId", "executionId", "ticketId", "ticketCycleId", "workflowInstanceId", "agentTaskId",
        "capabilityName", "connectorId", "status", "summary", "structuredOutput", "resultEnvelopeId", "evidenceRefs",
        "redactionStatus", "errorCode", "retryable",
    ):
        assert field in payload, field
    assert payload["status"] == "SUCCEEDED"
    assert payload["executionId"] is not None
    assert payload["resultEnvelopeId"] == executed.result_envelope_id
    assert payload["capabilityName"] == "kubernetes.fullPayloadOp"


def test_execute_resolves_and_reuses_credential_binding_for_same_connector_scope(container: Container) -> None:
    """SPEC-TG-012 11-security §"Credential Management": a real, persisted
    ``CredentialBinding`` is resolved on demand and reused across invocations
    for the same (connector, scope) — never minting a fresh vault_ref per call.
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.SUCCESS, summary="ok", structured_output={}, raw_output=None, error_code=None, retryable=False,
    )
    connector_id = _register_connector_with_fixed_outcome(
        container, "servicenow.credentialedOp", outcome, secret_requirements=("api-token",),
    )
    first_request_id = _submit_and_queue(container, "servicenow.credentialedOp", "idem-cred-1")
    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=first_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    first_binding = container.credential_binding_repository.find_active(connector_id, "api-token")
    assert first_binding is not None

    second_request_id = _submit_and_queue(container, "servicenow.credentialedOp", "idem-cred-2")
    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=second_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    second_binding = container.credential_binding_repository.find_active(connector_id, "api-token")
    assert second_binding is not None
    # Same underlying binding reused, not a freshly minted vault_ref.
    assert second_binding.credential_binding_id == first_binding.credential_binding_id
    assert second_binding.vault_ref == first_binding.vault_ref
    assert second_binding.last_used_at is not None
    assert second_binding.last_used_at >= first_binding.last_used_at


def _stuck_execution(status: ToolExecutionStatus, lease_expires_at: datetime) -> ToolExecution:
    now = lease_expires_at - timedelta(seconds=60)
    execution = ToolExecution.create(
        execution_id=ToolExecutionId.new_id(), tool_request_id=ToolRequestId.new_id(), attempt_number=1,
        connector_id=ConnectorId.new_id(), connector_version="1.0.0", side_effect_kind=SideEffectKind.READ_ONLY,
        operation_key=None,
    ).claim("worker-1", lease_expires_at, now)
    if status is ToolExecutionStatus.CLAIMED:
        return execution
    execution = execution.begin_preparing()
    if status is ToolExecutionStatus.PREPARING:
        return execution
    return execution.begin_invoking(lease_expires_at)


@pytest.mark.parametrize(
    ("stuck_status", "expected_status"),
    [
        (ToolExecutionStatus.CLAIMED, ToolExecutionStatus.LEASE_EXPIRED),
        (ToolExecutionStatus.PREPARING, ToolExecutionStatus.FAILED),
        (ToolExecutionStatus.INVOKING, ToolExecutionStatus.TIMED_OUT),
    ],
)
def test_reclaim_expired_leases_transitions_each_in_flight_status(
    container: Container, stuck_status: ToolExecutionStatus, expected_status: ToolExecutionStatus,
) -> None:
    """SPEC-TG-010 10-failure-handling §"Gateway Crash Recovery": "Scan lease-
    expired executions." Each in-flight status reclaims to a different target —
    see application.reclaim_expired_leases module docstring for why.
    """

    expired_lease = datetime.now(UTC) - timedelta(seconds=30)
    execution = _stuck_execution(stuck_status, expired_lease)
    assert execution.status is stuck_status
    container.tool_execution_repository.save(execution, expected_status=None)

    reclaimed_count = container.reclaim_expired_leases_service.reclaim_expired_leases(ReclaimExpiredLeasesCommand(batch_size=50))
    assert reclaimed_count == 1

    reloaded = container.tool_execution_repository.find_by_id(execution.execution_id)
    assert reloaded is not None
    assert reloaded.status is expected_status


def test_reclaim_expired_leases_ignores_leases_not_yet_expired(container: Container) -> None:
    future_lease = datetime.now(UTC) + timedelta(minutes=5)
    execution = _stuck_execution(ToolExecutionStatus.CLAIMED, future_lease)
    container.tool_execution_repository.save(execution, expected_status=None)

    reclaimed_count = container.reclaim_expired_leases_service.reclaim_expired_leases(ReclaimExpiredLeasesCommand(batch_size=50))
    assert reclaimed_count == 0

    reloaded = container.tool_execution_repository.find_by_id(execution.execution_id)
    assert reloaded is not None
    assert reloaded.status is ToolExecutionStatus.CLAIMED


def test_reclaim_expired_leases_writes_an_audit_record(container: Container) -> None:
    expired_lease = datetime.now(UTC) - timedelta(seconds=30)
    execution = _stuck_execution(ToolExecutionStatus.CLAIMED, expired_lease)
    container.tool_execution_repository.save(execution, expected_status=None)

    container.reclaim_expired_leases_service.reclaim_expired_leases(ReclaimExpiredLeasesCommand(batch_size=50))

    recent = container.audit_record_repository.find_recent(50)
    assert any(
        entry.action == "execution_lease_reclaimed" and entry.resource_id == str(execution.execution_id) for entry in recent
    )


def test_gateway_recovery_service_reclaims_leases_and_replays_outbox_together(container: Container) -> None:
    """SPEC-TG-030 "Crash Recovery Backpressure Scaling" 10-failure-handling
    §"Gateway Crash Recovery" steps 1-3, run together through the one entry
    point ``main.create_app`` never called automatically (see
    ``application.gateway_recovery`` module docstring) — this is the direct
    application-level equivalent of what ``POST /admin/recovery/run`` does
    over HTTP.
    """

    expired_lease = datetime.now(UTC) - timedelta(seconds=30)
    execution = _stuck_execution(ToolExecutionStatus.CLAIMED, expired_lease)
    container.tool_execution_repository.save(execution, expected_status=None)

    pending = OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(uuid.uuid4()),
        event_type="tool.completed.v1", event_version="1.0", payload={}, occurred_at=datetime.now(UTC),
        correlation_id=str(uuid.uuid4()),
    )
    container.outbox_repository.append(pending)

    summary = container.gateway_recovery_service.run_recovery(batch_size=50)
    assert summary.leases_reclaimed == 1
    assert summary.outbox_events_published == 1

    reloaded_execution = container.tool_execution_repository.find_by_id(execution.execution_id)
    assert reloaded_execution is not None
    assert reloaded_execution.status is ToolExecutionStatus.LEASE_EXPIRED


def _timed_out_and_executing(container: Container, capability: str, idempotency_key: str) -> str:
    outcome = ExecutionOutcome(
        status=ResultStatus.TIMED_OUT, summary="connector timed out", structured_output={}, raw_output=None,
        error_code="TIMEOUT", retryable=True,
    )
    _register_connector_with_fixed_outcome(container, capability, outcome)
    tool_request_id = _submit_and_queue(container, capability, idempotency_key)
    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "EXECUTING"
    return tool_request_id


def test_cancel_during_executing_enters_cancel_requested_and_does_not_auto_confirm(container: Container) -> None:
    """SPEC-TG-018 09-concurrency-and-idempotency §"Concurrent Cancellation":
    "Cancel commits first but connector was called: request enters
    CANCEL_REQUESTED and waits for connector hook/reconciliation" — no longer
    auto-confirmed to CANCELLED immediately (see cancel_tool_request's own
    module docstring for the SPEC-TG-001-era gap this closes).
    """

    tool_request_id = _timed_out_and_executing(container, "kubernetes.cancelDuringExec", "idem-cancel-exec-1")

    cancelled = container.cancel_tool_request_service.cancel_tool_request(CancelToolRequestCommand(
        tool_request_id=tool_request_id, idempotency_key=f"cancel-{uuid.uuid4()}", requested_by="agent-1",
        reason="no longer needed", correlation_id=str(uuid.uuid4()),
    ))
    assert cancelled.status == "CANCEL_REQUESTED"

    # No tool.cancelled.v1 yet — the cancellation is only requested, not final.
    assert not [e for e in _outbox_events(container, "tool.cancelled.v1") if e.payload["toolRequestId"] == tool_request_id]


def test_reconcile_after_cancel_requested_with_confirmed_failure_resolves_cancelled(container: Container) -> None:
    tool_request_id = _timed_out_and_executing(container, "kubernetes.cancelReconcileFail", "idem-cancel-reconcile-1")
    container.cancel_tool_request_service.cancel_tool_request(CancelToolRequestCommand(
        tool_request_id=tool_request_id, idempotency_key=f"cancel-{uuid.uuid4()}", requested_by="agent-1",
        reason="no longer needed", correlation_id=str(uuid.uuid4()),
    ))

    [attempt] = container.tool_execution_repository.find_attempts(ToolRequestId(uuid.UUID(tool_request_id)))
    reconciled = container.reconcile_execution_service.reconcile_execution(ReconcileExecutionCommand(
        execution_id=str(attempt.execution_id), correlation_id=str(uuid.uuid4()),
    ))
    assert reconciled.status == "CANCELLED"

    [event] = [e for e in _outbox_events(container, "tool.cancelled.v1") if e.payload["toolRequestId"] == tool_request_id]
    assert event.payload["status"] == "CANCELLED"


def test_reconcile_after_cancel_requested_with_confirmed_success_resolves_completed(container: Container) -> None:
    """09-concurrency-and-idempotency: a SUCCESS outcome resolving after a
    cancel was requested still lands COMPLETED — the side effect genuinely
    happened and cannot be un-done by a cancel that lost the race.
    """

    timed_out = ExecutionOutcome(
        status=ResultStatus.TIMED_OUT, summary="connector timed out", structured_output={}, raw_output=None,
        error_code="TIMEOUT", retryable=True,
    )
    success = ExecutionOutcome(
        status=ResultStatus.SUCCESS, summary="actually completed", structured_output={}, raw_output=None,
        error_code=None, retryable=False,
    )
    _register_connector_with_fixed_outcome(
        container, "kubernetes.cancelReconcileSuccess", timed_out, reconcile_outcome=success,
    )
    tool_request_id = _submit_and_queue(container, "kubernetes.cancelReconcileSuccess", "idem-cancel-reconcile-2")
    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    container.cancel_tool_request_service.cancel_tool_request(CancelToolRequestCommand(
        tool_request_id=tool_request_id, idempotency_key=f"cancel-{uuid.uuid4()}", requested_by="agent-1",
        reason="no longer needed", correlation_id=str(uuid.uuid4()),
    ))

    [attempt] = container.tool_execution_repository.find_attempts(ToolRequestId(uuid.UUID(tool_request_id)))
    reconciled = container.reconcile_execution_service.reconcile_execution(ReconcileExecutionCommand(
        execution_id=str(attempt.execution_id), correlation_id=str(uuid.uuid4()),
    ))
    assert reconciled.status == "COMPLETED"


def test_reconcile_uncertain_outcome_terminal_fails_without_retry_and_publishes_distinguishable_status(
    container: Container,
) -> None:
    """SPEC-TG-032 final coverage audit / 10-failure-handling §"Reconciliation":
    "If the result remains UNCERTAIN for too long, Gateway publishes final
    uncertain result and marks human handling required." Before this spec,
    UNCERTAIN fell into the exact same branch as a confirmed FAILED outcome —
    eligible for the same automatic retry a genuinely unknown outcome must
    never get (INV-TG-010: "must remain distinguishable... must not be
    collapsed into a generic failure").
    """

    timed_out = ExecutionOutcome(
        status=ResultStatus.TIMED_OUT, summary="connector timed out", structured_output={}, raw_output=None,
        error_code="TIMEOUT", retryable=True,
    )
    uncertain = ExecutionOutcome(
        status=ResultStatus.UNCERTAIN, summary="could not confirm whether the mutation applied", structured_output={},
        raw_output=None, error_code="RECONCILE_UNKNOWN", retryable=True,
    )
    _register_connector_with_fixed_outcome(
        container, "kubernetes.reconcileUncertain", timed_out, reconcile_outcome=uncertain, max_attempts=5,
    )
    tool_request_id = _submit_and_queue(container, "kubernetes.reconcileUncertain", "idem-reconcile-uncertain-1")
    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))

    [attempt] = container.tool_execution_repository.find_attempts(ToolRequestId(uuid.UUID(tool_request_id)))
    reconciled = container.reconcile_execution_service.reconcile_execution(ReconcileExecutionCommand(
        execution_id=str(attempt.execution_id), correlation_id=str(uuid.uuid4()),
    ))
    # Never re-queued for another attempt, even though max_attempts=5 leaves
    # plenty of retry budget and the outcome itself reports retryable=True —
    # UNCERTAIN overrides that.
    assert reconciled.status == "TERMINAL_FAILED"

    reloaded_attempt = container.tool_execution_repository.find_by_id(attempt.execution_id)
    assert reloaded_attempt is not None
    assert reloaded_attempt.status.name == "TERMINAL_FAILED"

    [event] = [e for e in _outbox_events(container, "tool.completed.v1") if e.payload["toolRequestId"] == tool_request_id]
    assert event.payload["status"] == "UNCERTAIN"

    [audit] = [
        a for a in container.audit_record_repository.find_recent(100)
        if a.action == "execution_uncertain_after_reconciliation" and a.tool_request_id == tool_request_id
    ]
    assert audit.outcome == "TERMINAL_FAILED"


def test_cancel_on_a_completed_request_is_a_noop_returning_final_status(container: Container) -> None:
    """09-concurrency-and-idempotency: "Completion commits first: cancel
    returns final completed."
    """

    _register_connector(container, "kubernetes.cancelAfterComplete", requires_approval=False, is_mutating=False)
    tool_request_id = _submit_and_queue(container, "kubernetes.cancelAfterComplete", "idem-cancel-complete-1")
    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))

    cancelled = container.cancel_tool_request_service.cancel_tool_request(CancelToolRequestCommand(
        tool_request_id=tool_request_id, idempotency_key=f"cancel-{uuid.uuid4()}", requested_by="agent-1",
        reason="too late", correlation_id=str(uuid.uuid4()),
    ))
    assert cancelled.status == "COMPLETED"


def test_repeat_cancel_while_cancel_requested_is_a_noop(container: Container) -> None:
    tool_request_id = _timed_out_and_executing(container, "kubernetes.cancelTwiceExec", "idem-cancel-exec-2")
    command = CancelToolRequestCommand(
        tool_request_id=tool_request_id, idempotency_key=f"cancel-{uuid.uuid4()}", requested_by="agent-1",
        reason="no longer needed", correlation_id=str(uuid.uuid4()),
    )
    first = container.cancel_tool_request_service.cancel_tool_request(command)
    second = container.cancel_tool_request_service.cancel_tool_request(command)
    assert first.status == "CANCEL_REQUESTED"
    assert second.status == "CANCEL_REQUESTED"


def test_update_connector_status_publishes_health_changed_event(container: Container) -> None:
    """SPEC-TG-019 06-event-contracts §"tool.connector.health_changed.v1":
    "Published after connector health changes" — admin-driven ENABLE/DISABLE/
    DEPRECATE never published this before this spec.
    """

    registered = _register_connector(container, "kubernetes.healthEventOnDisable", requires_approval=False, is_mutating=False)
    container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=registered.connector_id, action="DISABLE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))
    [event] = [
        e for e in _outbox_events(container, "tool.connector.health_changed.v1") if e.payload["connectorId"] == registered.connector_id
    ]
    assert event.payload["healthStatus"] == "DISABLED"


def test_apply_health_check_result_degrades_active_connector_after_consecutive_failures_and_reactivates(container: Container) -> None:
    """SPEC-TG-019 03-state-machine §"Connector Health State Machine" /
    SPEC-TG-030 10-failure-handling §"Connector Crash Or Unavailability":
    "Consecutive failures move an ACTIVE connector to DEGRADED" — the literal
    plural means a threshold of consecutive failures
    (``settings.connector_degrade_after_failures``, default 3), not a single
    one; a lone failure only advances the counter and leaves the connector
    ACTIVE. Before SPEC-TG-030 a single failed check degraded it immediately.
    """

    registered = _register_connector(container, "kubernetes.autoHealthToggle", requires_approval=False, is_mutating=False)
    connector_id = ConnectorId(uuid.UUID(registered.connector_id))

    first_failure = container.register_connector_service.apply_health_check_result(
        connector_id, healthy=False, correlation_id=str(uuid.uuid4()),
    )
    assert first_failure.health_status == "ACTIVE"
    assert first_failure.consecutive_health_check_failures == 1

    container.register_connector_service.apply_health_check_result(connector_id, healthy=False, correlation_id=str(uuid.uuid4()))
    degraded = container.register_connector_service.apply_health_check_result(
        connector_id, healthy=False, correlation_id=str(uuid.uuid4()),
    )
    assert degraded.health_status == "DEGRADED"
    assert degraded.consecutive_health_check_failures == 3

    reactivated = container.register_connector_service.apply_health_check_result(
        connector_id, healthy=True, correlation_id=str(uuid.uuid4()),
    )
    assert reactivated.health_status == "ACTIVE"
    assert reactivated.consecutive_health_check_failures == 0

    events = [
        e for e in _outbox_events(container, "tool.connector.health_changed.v1") if e.payload["connectorId"] == registered.connector_id
    ]
    assert [e.payload["healthStatus"] for e in events] == ["DEGRADED", "ACTIVE"]


def test_apply_health_check_result_disables_connector_after_further_consecutive_failures_while_degraded(container: Container) -> None:
    """SPEC-TG-030 10-failure-handling §"Connector Crash Or Unavailability":
    "Health check failures beyond threshold move it to DISABLED." Before this
    spec, a connector already DEGRADED that kept failing every subsequent
    health check fell into the unconditional no-op branch forever — never
    escalated, never audited, never published, with no path to DISABLED short
    of a human admin noticing and acting.
    """

    registered = _register_connector(container, "kubernetes.autoHealthDisable", requires_approval=False, is_mutating=False)
    connector_id = ConnectorId(uuid.UUID(registered.connector_id))

    result = None
    for _ in range(5):
        result = container.register_connector_service.apply_health_check_result(
            connector_id, healthy=False, correlation_id=str(uuid.uuid4()),
        )
    assert result is not None
    assert result.health_status == "DISABLED"
    assert result.consecutive_health_check_failures == 5

    # DISABLED never auto-reactivates from a healthy probe — that stays an
    # admin-driven decision via ``update_connector_status``.
    still_disabled = container.register_connector_service.apply_health_check_result(
        connector_id, healthy=True, correlation_id=str(uuid.uuid4()),
    )
    assert still_disabled.health_status == "DISABLED"

    events = [
        e for e in _outbox_events(container, "tool.connector.health_changed.v1") if e.payload["connectorId"] == registered.connector_id
    ]
    assert [e.payload["healthStatus"] for e in events] == ["DEGRADED", "DISABLED"]


def test_apply_health_check_result_is_a_noop_for_disabled_connector(container: Container) -> None:
    registered = _register_connector(container, "kubernetes.autoHealthNoopDisabled", requires_approval=False, is_mutating=False)
    connector_id = ConnectorId(uuid.UUID(registered.connector_id))
    container.register_connector_service.update_connector_status(UpdateConnectorStatusCommand(
        connector_id=registered.connector_id, action="DISABLE", requested_by="admin-1", correlation_id=str(uuid.uuid4()),
    ))

    result = container.register_connector_service.apply_health_check_result(
        connector_id, healthy=True, correlation_id=str(uuid.uuid4()),
    )
    assert result.health_status == "DISABLED"


def test_degraded_read_only_connector_still_schedulable_as_fallback(container: Container) -> None:
    """SPEC-TG-019 03-state-machine: "A DEGRADED connector is allowed only for
    read-only or low-risk fallback." Execution must still succeed against a
    DEGRADED read-only connector when no ACTIVE alternative exists.
    """

    registered = _register_connector(container, "kubernetes.degradedFallback", requires_approval=False, is_mutating=False)
    connector_id = ConnectorId(uuid.UUID(registered.connector_id))
    container.connector_registry_port.save(container.connector_registry_port.find_by_id(connector_id).degrade())

    tool_request_id = _submit_and_queue(container, "kubernetes.degradedFallback", "idem-degraded-fallback-1")
    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    assert executed.status == "COMPLETED"


def test_degraded_mutating_high_risk_connector_is_not_schedulable(container: Container) -> None:
    """The DEGRADED fallback is narrow — a mutating, high-risk connector must
    NOT be selected while DEGRADED, even with no ACTIVE alternative.
    """

    registered = container.register_connector_service.register_connector(RegisterConnectorCommand(
        name="degraded-mutating-connector", version="1.0.0", capability_names=("kubernetes.degradedMutating",),
        input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1", risk_level="HIGH",
        requires_approval=False, is_mutating=True, correlation_id=str(uuid.uuid4()),
    ))
    connector_id = ConnectorId(uuid.UUID(registered.connector_id))
    container.connector_registry_port.save(container.connector_registry_port.find_by_id(connector_id).degrade())

    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-degraded-no-fallback-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.degradedMutating", input_payload={}, reason="try it", correlation_id=str(uuid.uuid4()),
    ))
    # CapabilityNotRegisteredException maps to REJECTED at create time — no
    # ACTIVE/eligible-DEGRADED connector currently backs the capability.
    assert created.status == "REJECTED"


def test_execute_success_stores_raw_output_and_returns_a_controlled_reference(container: Container) -> None:
    """SPEC-TG-020 INV-TG-007: "Raw output can be read only through
    controlled storage references." The result API/event never carry raw
    content itself, only ``raw_output_ref``.
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.SUCCESS, summary="ok", structured_output={}, raw_output="the full unredacted raw payload",
        error_code=None, retryable=False,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.rawOutputOp", outcome)
    tool_request_id = _submit_and_queue(container, "kubernetes.rawOutputOp", "idem-raw-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    result = container.execute_tool_request_service.find_result(executed.result_envelope_id)
    assert result.raw_output_ref is not None
    assert "the full unredacted raw payload" not in str(result.raw_output_ref)

    raw = container.execute_tool_request_service.find_raw_output(
        executed.result_envelope_id, "HUMAN_OPERATOR", "admin-1", "investigating incident INC-1", str(uuid.uuid4()),
    )
    assert raw.raw_output == "the full unredacted raw payload"


def test_find_raw_output_forbidden_for_agent_requester(container: Container) -> None:
    """11-security §"Agent Isolation": "Agent must not see: ... raw output.\""""

    outcome = ExecutionOutcome(
        status=ResultStatus.SUCCESS, summary="ok", structured_output={}, raw_output="secret raw content",
        error_code=None, retryable=False,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.rawOutputAgentDenied", outcome)
    tool_request_id = _submit_and_queue(container, "kubernetes.rawOutputAgentDenied", "idem-raw-2")
    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))

    with pytest.raises(RawOutputForbiddenException):
        container.execute_tool_request_service.find_raw_output(
            executed.result_envelope_id, "AGENT", "triage-agent", "trying to peek", str(uuid.uuid4()),
        )

    recent = container.audit_record_repository.find_recent(50)
    assert any(entry.action == "raw_output_access_denied" for entry in recent)


def test_find_raw_output_forbidden_without_a_reason(container: Container) -> None:
    """05-api-contracts §"Result API": "Requires privileged RBAC, audit
    reason, and policy check."
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.SUCCESS, summary="ok", structured_output={}, raw_output="secret raw content",
        error_code=None, retryable=False,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.rawOutputNoReason", outcome)
    tool_request_id = _submit_and_queue(container, "kubernetes.rawOutputNoReason", "idem-raw-3")
    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))

    with pytest.raises(RawOutputForbiddenException):
        container.execute_tool_request_service.find_raw_output(
            executed.result_envelope_id, "HUMAN_OPERATOR", "admin-1", "   ", str(uuid.uuid4()),
        )


def test_find_raw_output_returns_none_when_no_raw_output_was_ever_produced(container: Container) -> None:
    """A connector that never produced raw output is a legitimate fact, not a
    denial — the endpoint still succeeds, just with ``raw_output: null``.
    """

    _register_connector(container, "kubernetes.noRawOutputOp", requires_approval=False, is_mutating=False)
    tool_request_id = _submit_and_queue(container, "kubernetes.noRawOutputOp", "idem-raw-4")
    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))

    raw = container.execute_tool_request_service.find_raw_output(
        executed.result_envelope_id, "HUMAN_OPERATOR", "admin-1", "double checking", str(uuid.uuid4()),
    )
    assert raw.raw_output is None


def test_create_tool_request_rejected_when_requester_type_not_allowed(container: Container) -> None:
    """SPEC-TG-021 INV-TG-009: "Runtime visibility of a capability does not
    mean an Agent may execute it." A connector may restrict itself to
    HUMAN_OPERATOR only.
    """

    container.register_connector_service.register_connector(RegisterConnectorCommand(
        name="human-only-connector", version="1.0.0", capability_names=("kubernetes.humanOnlyOp",),
        input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1", risk_level="LOW",
        requires_approval=False, is_mutating=False, allowed_requester_types=("HUMAN_OPERATOR",),
        correlation_id=str(uuid.uuid4()),
    ))

    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-requester-denied-1", requested_by_type="AGENT", requested_by_id="triage-agent",
        capability_name="kubernetes.humanOnlyOp", input_payload={}, reason="try it", correlation_id=str(uuid.uuid4()),
    ))
    assert created.status == "REJECTED"
    assert "requester type" in (created.denial_reason or "")

    rejected_events = [e for e in _outbox_events(container, "tool.request.rejected.v1") if e.payload["toolRequestId"] == created.tool_request_id]
    assert rejected_events


def test_create_tool_request_accepted_when_requester_type_is_allowed(container: Container) -> None:
    container.register_connector_service.register_connector(RegisterConnectorCommand(
        name="human-only-connector-2", version="1.0.0", capability_names=("kubernetes.humanOnlyOp2",),
        input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1", risk_level="LOW",
        requires_approval=False, is_mutating=False, allowed_requester_types=("HUMAN_OPERATOR",),
        correlation_id=str(uuid.uuid4()),
    ))

    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-requester-allowed-1", requested_by_type="HUMAN_OPERATOR", requested_by_id="admin-1",
        capability_name="kubernetes.humanOnlyOp2", input_payload={}, reason="try it", correlation_id=str(uuid.uuid4()),
    ))
    assert created.status == "VALIDATING"


def test_consume_workflow_cancelled_cancels_queued_tool_requests_for_that_workflow(container: Container) -> None:
    """SPEC-TG-022 06-event-contracts §"workflow.cancelled.v1": "when Runtime
    workflow is cancelled, Gateway attempts to cancel associated pending/
    running Tool Requests."
    """

    _register_connector(container, "kubernetes.workflowCancelOp", requires_approval=False, is_mutating=False)
    workflow_instance_id = str(uuid.uuid4())
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-wf-cancel-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.workflowCancelOp", input_payload={}, reason="investigate",
        correlation_id=str(uuid.uuid4()), workflow_instance_id=workflow_instance_id,
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(created.tool_request_id)))
    assert stored is not None and stored.status is ToolRequestStatus.QUEUED

    cancelled_count = container.workflow_cancelled_consumer_port.consume_workflow_cancelled(ConsumeWorkflowCancelledCommand(
        event_id=f"evt-{uuid.uuid4()}", workflow_instance_id=workflow_instance_id, correlation_id=str(uuid.uuid4()),
    ))
    assert cancelled_count == 1

    reloaded = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(created.tool_request_id)))
    assert reloaded is not None and reloaded.status is ToolRequestStatus.CANCELLED


def test_consume_workflow_cancelled_skips_requests_with_no_cancel_edge(container: Container) -> None:
    """A request sitting WAITING_APPROVAL has no cancel edge in 03-state-
    machine's own transition table — "attempts to cancel" tolerates this,
    doesn't fail the whole batch.
    """

    _register_connector(container, "kubernetes.restartWorkflowCancelWaiting", requires_approval=True, is_mutating=True)
    workflow_instance_id = str(uuid.uuid4())
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-wf-cancel-2", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.restartWorkflowCancelWaiting", input_payload={}, reason="investigate",
        correlation_id=str(uuid.uuid4()), workflow_instance_id=workflow_instance_id,
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    stored = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(created.tool_request_id)))
    assert stored is not None and stored.status is ToolRequestStatus.WAITING_APPROVAL

    cancelled_count = container.workflow_cancelled_consumer_port.consume_workflow_cancelled(ConsumeWorkflowCancelledCommand(
        event_id=f"evt-{uuid.uuid4()}", workflow_instance_id=workflow_instance_id, correlation_id=str(uuid.uuid4()),
    ))
    assert cancelled_count == 0

    reloaded = container.tool_request_repository.find_by_id(ToolRequestId(uuid.UUID(created.tool_request_id)))
    assert reloaded is not None and reloaded.status is ToolRequestStatus.WAITING_APPROVAL


def test_consume_workflow_cancelled_deduplicates_by_event_id(container: Container) -> None:
    _register_connector(container, "kubernetes.workflowCancelDedup", requires_approval=False, is_mutating=False)
    workflow_instance_id = str(uuid.uuid4())
    created = container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-wf-cancel-3", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.workflowCancelDedup", input_payload={}, reason="investigate",
        correlation_id=str(uuid.uuid4()), workflow_instance_id=workflow_instance_id,
    ))
    container.evaluate_tool_request_service.evaluate_tool_request(EvaluateToolRequestCommand(
        tool_request_id=created.tool_request_id, correlation_id=str(uuid.uuid4()),
    ))
    event_id = f"evt-{uuid.uuid4()}"
    command = ConsumeWorkflowCancelledCommand(event_id=event_id, workflow_instance_id=workflow_instance_id, correlation_id=str(uuid.uuid4()))

    first = container.workflow_cancelled_consumer_port.consume_workflow_cancelled(command)
    second = container.workflow_cancelled_consumer_port.consume_workflow_cancelled(command)
    assert first == 1
    assert second == 0


def test_execute_success_populates_evidence_refs_with_the_raw_output_reference(container: Container) -> None:
    """SPEC-TG-024 04-memory-knowledge §"Consumed Events": "obtain ... tool
    evidence refs." Never the raw content itself — INV-TG-007.
    """

    outcome = ExecutionOutcome(
        status=ResultStatus.SUCCESS, summary="ok", structured_output={}, raw_output="full raw payload",
        error_code=None, retryable=False,
    )
    _register_connector_with_fixed_outcome(container, "kubernetes.evidenceRefsOp", outcome)
    tool_request_id = _submit_and_queue(container, "kubernetes.evidenceRefsOp", "idem-evidence-1")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    result = container.execute_tool_request_service.find_result(executed.result_envelope_id)
    assert result.evidence_refs == (result.raw_output_ref,)
    assert "full raw payload" not in str(result.evidence_refs)

    [event] = [e for e in _outbox_events(container, "tool.completed.v1") if e.payload["toolRequestId"] == tool_request_id]
    assert event.payload["evidenceRefs"] == [result.raw_output_ref]


def test_execute_success_without_raw_output_leaves_evidence_refs_empty(container: Container) -> None:
    _register_connector(container, "kubernetes.noEvidenceOp", requires_approval=False, is_mutating=False)
    tool_request_id = _submit_and_queue(container, "kubernetes.noEvidenceOp", "idem-evidence-2")

    executed = container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))
    result = container.execute_tool_request_service.find_result(executed.result_envelope_id)
    assert result.evidence_refs == ()


def test_audit_query_find_by_ticket_id(container: Container) -> None:
    """SPEC-TG-027 12-observability §"Audit Observability": "all tool
    executions by ticket."
    """

    _register_connector(container, "kubernetes.auditByTicketOp", requires_approval=False, is_mutating=False)
    ticket_id = str(uuid.uuid4())
    container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-audit-ticket-1", requested_by_type="AGENT", requested_by_id="agent-1",
        capability_name="kubernetes.auditByTicketOp", input_payload={}, reason="investigate",
        correlation_id=str(uuid.uuid4()), ticket_id=ticket_id,
    ))
    other_ticket_entries = container.audit_query_service.find_by_ticket_id(str(uuid.uuid4()), 50)
    matching_entries = container.audit_query_service.find_by_ticket_id(ticket_id, 50)
    assert not other_ticket_entries
    assert any(e.action == "request_accepted" for e in matching_entries)


def test_audit_query_find_by_actor_id(container: Container) -> None:
    """12-observability §"Audit Observability": "tool requests by actor.\""""

    _register_connector(container, "kubernetes.auditByActorOp", requires_approval=False, is_mutating=False)
    container.create_tool_request_service.create_tool_request(CreateToolRequestCommand(
        idempotency_key="idem-audit-actor-1", requested_by_type="AGENT", requested_by_id="distinctive-agent-77",
        capability_name="kubernetes.auditByActorOp", input_payload={}, reason="investigate",
        correlation_id=str(uuid.uuid4()),
    ))
    entries = container.audit_query_service.find_by_actor_id("distinctive-agent-77", 50)
    assert any(e.action == "request_accepted" and e.actor_id == "distinctive-agent-77" for e in entries)


def test_audit_query_find_by_connector_id(container: Container) -> None:
    """12-observability §"Audit Observability": "failures and credential
    usage by connector" — needs a real connector_id association per audit
    entry, not just resource_type/resource_id (a credential_binding_resolved
    entry's own resource is the execution, not the connector).
    """

    registered = _register_connector(container, "kubernetes.auditByConnectorOp", requires_approval=False, is_mutating=False)
    tool_request_id = _submit_and_queue(container, "kubernetes.auditByConnectorOp", "idem-audit-connector-1")
    container.execute_tool_request_service.execute_tool_request(ExecuteToolRequestCommand(
        tool_request_id=tool_request_id, lease_owner="worker-1", correlation_id=str(uuid.uuid4()),
    ))

    entries = container.audit_query_service.find_by_connector_id(registered.connector_id, 50)
    assert any(e.action == "execution_started" for e in entries)
    assert any(e.action == "result_published" for e in entries)


def test_admin_outbox_replay_moves_dead_letter_back_to_pending(container: Container) -> None:
    """SPEC-TG-028 10-failure-handling §"Poison Request": "outbox publication
    fails beyond threshold" -> dead-letter; admin repair moves it back.
    """

    record = OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(uuid.uuid4()),
        event_type="tool.completed.v1", event_version="1.0", payload={}, occurred_at=container.clock.now(),
        correlation_id=str(uuid.uuid4()),
    )
    container.outbox_repository.append(record)
    container.outbox_repository.mark_dead_letter(record.outbox_id)
    assert any(r.outbox_id == str(record.outbox_id) for r in container.admin_outbox_service.list_dead_letter(50))

    replayed = container.admin_outbox_service.replay(str(record.outbox_id), "admin-1", str(uuid.uuid4()))
    assert replayed.status == "PENDING"
    assert replayed.attempts == 0
    assert not any(r.outbox_id == str(record.outbox_id) for r in container.admin_outbox_service.list_dead_letter(50))

    recent = container.audit_record_repository.find_recent(50)
    assert any(e.action == "outbox_event_replayed" and e.resource_id == str(record.outbox_id) for e in recent)


def test_admin_outbox_replay_raises_for_unknown_outbox_id(container: Container) -> None:
    with pytest.raises(OutboxRecordNotFoundException):
        container.admin_outbox_service.replay(str(uuid.uuid4()), "admin-1", str(uuid.uuid4()))


def test_admin_outbox_replay_raises_for_non_dead_letter_record(container: Container) -> None:
    record = OutboxRecord(
        outbox_id=uuid.uuid4(), aggregate_type="TOOL_REQUEST", aggregate_id=str(uuid.uuid4()),
        event_type="tool.completed.v1", event_version="1.0", payload={}, occurred_at=container.clock.now(),
        correlation_id=str(uuid.uuid4()),
    )
    container.outbox_repository.append(record)

    with pytest.raises(OutboxRecordNotDeadLetterException):
        container.admin_outbox_service.replay(str(record.outbox_id), "admin-1", str(uuid.uuid4()))


def test_find_connector_returns_full_manifest(container: Container) -> None:
    """SPEC-TG-029 "Connector Admin Lifecycle API": ``GET /connectors/{id}``
    — the summary ``list_connectors`` view gave no visibility into secrets/
    network policy/timeouts/retry policy/requester restriction.
    """

    container.register_connector_service.register_connector(RegisterConnectorCommand(
        name="full-manifest-connector", version="1.0.0", capability_names=("kubernetes.fullManifestOp",),
        input_schema_ref="schema://input/v1", output_schema_ref="schema://output/v1", risk_level="LOW",
        requires_approval=False, is_mutating=False, secret_requirements=("api-token",),
        allowed_hosts=("api.internal.example",), max_attempts=5, backoff_seconds=10,
        allowed_requester_types=("HUMAN_OPERATOR",), correlation_id=str(uuid.uuid4()),
    ))
    [registered] = [
        v for v in container.register_connector_service.list_connectors() if v.name == "full-manifest-connector"
    ]

    found = container.register_connector_service.find_connector(registered.connector_id)
    assert found.secret_requirements == ("api-token",)
    assert found.allowed_hosts == ("api.internal.example",)
    assert found.max_attempts == 5
    assert found.backoff_seconds == 10
    assert found.allowed_requester_types == ("HUMAN_OPERATOR",)


def test_find_connector_raises_for_unknown_id(container: Container) -> None:
    with pytest.raises(ConnectorNotFoundException):
        container.register_connector_service.find_connector(str(uuid.uuid4()))
