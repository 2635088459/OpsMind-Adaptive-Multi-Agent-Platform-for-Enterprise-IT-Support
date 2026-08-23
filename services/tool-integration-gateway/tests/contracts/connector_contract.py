"""SPEC-TG-011 14-testing-strategy §"Connector Contract Tests": "Every
connector must pass contracts: valid manifest schema; input schema validation;
output schema normalization; timeout behavior; retryable/non-retryable error
mapping; reconcile/cancel hook behavior; no secret in output/log." Reusable
assertions applied against every ``ports.connector_port.ConnectorPort``
implementation under ``adapters.connectors.builtin`` — not a test module of its
own (no ``test_`` prefix; pytest never collects it directly), imported by
``tests/domain/test_builtin_connectors.py`` for each concrete adapter.

"Valid manifest schema" is checked at connector *registration* (see
``domain.connector.ToolConnector.register()`` and its own tests), not by a
connector adapter itself — a ``ConnectorPort`` implementation has no manifest
of its own to validate, only behavior. This suite covers the behavioral half
of the contract list.
"""

from __future__ import annotations

from tool_gateway.domain.enums import ResultStatus
from tool_gateway.domain.values import ConnectorInvocationSpec, ExecutionOutcome
from tool_gateway.ports.connector_port import ConnectorPort

_CREDENTIAL_MARKER = "cred-handle-should-never-leak"


def _spec(operation_key: str | None = "op-1") -> ConnectorInvocationSpec:
    return ConnectorInvocationSpec(
        connector_id="connector-1", connector_version="1.0.0", operation_key=operation_key,
        input_payload={"key": "value"}, timeout_seconds=30, credential_binding_id=_CREDENTIAL_MARKER,
    )


def assert_connector_contract(connector: ConnectorPort) -> None:
    """Runs the shared behavioral assertions every built-in connector must
    satisfy, regardless of what outcome it is configured to return.
    """

    # input schema validation: must not raise for a well-formed spec.
    connector.validate_input(_spec())

    # output schema normalization: invoke() returns a real ExecutionOutcome.
    outcome = connector.invoke(_spec())
    assert isinstance(outcome, ExecutionOutcome)
    assert outcome.status in ResultStatus
    assert isinstance(outcome.summary, str)
    assert isinstance(outcome.structured_output, dict)
    # retryable/non-retryable error mapping: the field must always be a bool,
    # never left unset/None, whatever the outcome status is.
    assert isinstance(outcome.retryable, bool)

    # reconcile/cancel hook behavior: both must be callable without raising.
    connector.reconcile(_spec())
    connector.cancel(_spec())

    # health_check() returns a plain bool.
    assert isinstance(connector.health_check(), bool)

    # no secret in output/log: the opaque credential_binding_id the caller
    # handed the connector must never be echoed back as if it were content —
    # a connector that logs/returns what it was given as a credential handle
    # is exactly the leak INV-TG-004 forbids.
    assert _CREDENTIAL_MARKER not in outcome.summary
    assert _CREDENTIAL_MARKER not in str(outcome.structured_output)
