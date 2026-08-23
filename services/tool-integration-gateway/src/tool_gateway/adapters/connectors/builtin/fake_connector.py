"""SPEC-TG-011 "Connector SDK And Built-In Fake Connector" / 14-testing-strategy
§"Connector Contract Tests": a configurable connector double. Unlike
``EchoConnectorAdapter`` (always ``SUCCESS`` — the default every
``RegisterConnectorCommand`` binds to), ``FakeConnectorAdapter`` can be
constructed with any ``ExecutionOutcome`` (TIMED_OUT/FAILED/
PARTIAL_SIDE_EFFECT/SUCCESS), so tests can exercise
``execute_tool_request``'s/``reconcile_execution``'s non-SUCCESS branches
without a real external system. Promoted from a test-only double
(previously duplicated inside ``tests/application/test_full_flow.py`` as
``_FixedOutcomeConnector``) into a real ``adapters/connectors/builtin/`` module
per this spec's own name — the single implementation the connector contract
test suite (``tests/contracts/connector_contract.py``) and application-level
tests both reuse instead of drifting copies.

SPEC-TG-017 added an optional, independent ``reconcile_outcome`` — a real
connector's ``reconcile()`` performs a genuinely different status lookup than
``invoke()`` (04-use-cases UC-TG-005 step 3: "queries the external system or
connector status endpoint"), so tests exercising reconciliation (e.g. "invoke
times out, reconcile later confirms success") need the two to differ; defaults
to ``outcome`` (the prior behavior, inherited from ``BaseConnector.reconcile()``
re-invoking) when not given.
"""

from __future__ import annotations

from tool_gateway.adapters.connectors.base import BaseConnector
from tool_gateway.domain.values import ConnectorInvocationSpec, ExecutionOutcome


class FakeConnectorAdapter(BaseConnector):
    def __init__(self, outcome: ExecutionOutcome, reconcile_outcome: ExecutionOutcome | None = None) -> None:
        self._outcome = outcome
        self._reconcile_outcome = reconcile_outcome if reconcile_outcome is not None else outcome

    def invoke(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome:
        return self._outcome

    def reconcile(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome:
        return self._reconcile_outcome
