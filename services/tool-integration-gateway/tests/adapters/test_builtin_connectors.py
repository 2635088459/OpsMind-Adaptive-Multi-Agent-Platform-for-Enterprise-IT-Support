"""SPEC-TG-011 14-testing-strategy §"Connector Contract Tests": every built-in
connector (adapters.connectors.builtin) must pass
tests/contracts/connector_contract.py's shared assertions.
"""

from __future__ import annotations

from tests.contracts.connector_contract import assert_connector_contract

from tool_gateway.adapters.connectors.builtin.echo_connector import EchoConnectorAdapter
from tool_gateway.adapters.connectors.builtin.fake_connector import FakeConnectorAdapter
from tool_gateway.domain.enums import ResultStatus
from tool_gateway.domain.values import ConnectorInvocationSpec, ExecutionOutcome


def test_echo_connector_satisfies_the_contract() -> None:
    assert_connector_contract(EchoConnectorAdapter())


def test_fake_connector_satisfies_the_contract_for_every_outcome_status() -> None:
    for status in ResultStatus:
        outcome = ExecutionOutcome(
            status=status, summary=f"simulated {status.name.lower()} outcome", structured_output={"status": status.name},
            raw_output=None, error_code=None if status is ResultStatus.SUCCESS else "SIMULATED_ERROR",
            retryable=status in (ResultStatus.FAILED, ResultStatus.TIMED_OUT),
        )
        assert_connector_contract(FakeConnectorAdapter(outcome))


def test_fake_connector_returns_the_configured_outcome_verbatim() -> None:
    outcome = ExecutionOutcome(
        status=ResultStatus.TIMED_OUT, summary="connector timed out", structured_output={}, raw_output=None,
        error_code="TIMEOUT", retryable=True,
    )
    connector = FakeConnectorAdapter(outcome)

    spec = ConnectorInvocationSpec(
        connector_id="c-1", connector_version="1.0.0", operation_key="op-1", input_payload={}, timeout_seconds=30,
    )
    assert connector.invoke(spec) is outcome
    # BaseConnector's own default reconcile() re-invokes — a naive but honest
    # default for a connector with no real status-lookup endpoint.
    assert connector.reconcile(spec) is outcome
