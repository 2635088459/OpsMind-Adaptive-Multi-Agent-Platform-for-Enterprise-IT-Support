"""03-state-machine §"Execution Attempt State Machine" + domain-rules
§"Required": "Mutation connectors must have an operation key."
"""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from tool_gateway.domain.enums import SideEffectKind, ToolExecutionStatus
from tool_gateway.domain.errors import InvalidToolExecutionTransitionException, MutationConnectorMissingOperationKeyException
from tool_gateway.domain.ids import ConnectorId, OperationKey, ToolExecutionId, ToolRequestId
from tool_gateway.domain.tool_execution import ToolExecution

_NOW = datetime(2026, 1, 1, tzinfo=UTC)


def _create(side_effect_kind: SideEffectKind = SideEffectKind.READ_ONLY, operation_key: OperationKey | None = None) -> ToolExecution:
    return ToolExecution.create(
        execution_id=ToolExecutionId.new_id(), tool_request_id=ToolRequestId.new_id(), attempt_number=1,
        connector_id=ConnectorId.new_id(), connector_version="1.0.0", side_effect_kind=side_effect_kind,
        operation_key=operation_key,
    )


def test_mutating_connector_requires_operation_key() -> None:
    with pytest.raises(MutationConnectorMissingOperationKeyException):
        _create(side_effect_kind=SideEffectKind.MUTATING, operation_key=None)


def test_mutating_connector_with_operation_key_succeeds() -> None:
    execution = _create(side_effect_kind=SideEffectKind.MUTATING, operation_key=OperationKey("op-1"))
    assert execution.status is ToolExecutionStatus.CREATED


def test_happy_path_reaches_completed() -> None:
    execution = _create()
    execution = execution.claim("worker-1", _NOW, _NOW).begin_preparing().begin_invoking(_NOW).begin_normalizing()
    completed = execution.complete(result_envelope_id=None, now=_NOW)
    assert completed.status is ToolExecutionStatus.COMPLETED
    assert completed.status.is_terminal()


def test_timeout_leads_to_reconciling_then_completed() -> None:
    """04-use-cases UC-TG-005."""

    execution = _create().claim("worker-1", _NOW, _NOW).begin_preparing().begin_invoking(_NOW).time_out()
    assert execution.status is ToolExecutionStatus.TIMED_OUT
    reconciling = execution.begin_reconciling()
    completed = reconciling.reconcile_complete(result_envelope_id=None, now=_NOW)
    assert completed.status is ToolExecutionStatus.COMPLETED


def test_invalid_transition_raises() -> None:
    execution = _create()
    with pytest.raises(InvalidToolExecutionTransitionException):
        execution.complete(result_envelope_id=None, now=_NOW)


def test_expire_lease_from_claimed() -> None:
    """SPEC-TG-010: a worker that died between claim() and begin_preparing()
    never invoked the connector — expire_lease() fails the attempt directly,
    never routing it through reconciliation (see
    application.reclaim_expired_leases module docstring).
    """

    execution = _create().claim("worker-1", _NOW, _NOW)
    expired = execution.expire_lease()
    assert expired.status is ToolExecutionStatus.LEASE_EXPIRED
    assert expired.status.is_terminal()


def test_fail_preparing_from_preparing() -> None:
    """SPEC-TG-010: a worker that died mid-PREPARING also never invoked the
    connector — fail_preparing() fails it directly, same reasoning as
    expire_lease().
    """

    execution = _create().claim("worker-1", _NOW, _NOW).begin_preparing()
    failed = execution.fail_preparing()
    assert failed.status is ToolExecutionStatus.FAILED
