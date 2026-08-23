"""SPEC-TG-019 03-state-machine §"Connector Health State Machine":
``ConnectorHealthWorker`` probes every ACTIVE/DEGRADED connector's own
``ConnectorPort.health_check()`` and drives the automatic ACTIVE<->DEGRADED
transition through ``RegisterConnectorUseCase.apply_health_check_result()`` —
never mutating the registry directly (see that worker's own module docstring).
"""

from __future__ import annotations

from tool_gateway.domain.enums import ConnectorHealthStatus
from tool_gateway.workers.connector_health_worker import ConnectorHealthWorker


class _FakeConnector:
    def __init__(self, connector_id: str, health_status: ConnectorHealthStatus) -> None:
        self.connector_id = connector_id
        self.health_status = health_status


class _FakeAdapter:
    def __init__(self, healthy: bool) -> None:
        self._healthy = healthy

    def health_check(self) -> bool:
        return self._healthy


class _StubConnectorRegistryPort:
    def __init__(self, connectors: list[_FakeConnector], adapters: dict[str, _FakeAdapter]) -> None:
        self._connectors = connectors
        self._adapters = adapters

    def list_all(self) -> list[_FakeConnector]:
        return self._connectors

    def get_adapter(self, connector_id: str) -> _FakeAdapter:
        return self._adapters[connector_id]


class _StubApplyHealthCheckPort:
    def __init__(self) -> None:
        self.calls: list[tuple[str, bool]] = []

    def apply_health_check_result(self, connector_id: str, healthy: bool, correlation_id: str):
        self.calls.append((connector_id, healthy))
        return None


def test_run_once_checks_every_active_and_degraded_connector() -> None:
    connectors = [
        _FakeConnector("c-active", ConnectorHealthStatus.ACTIVE), _FakeConnector("c-degraded", ConnectorHealthStatus.DEGRADED),
    ]
    adapters = {"c-active": _FakeAdapter(healthy=True), "c-degraded": _FakeAdapter(healthy=False)}
    registry = _StubConnectorRegistryPort(connectors, adapters)
    apply_port = _StubApplyHealthCheckPort()
    worker = ConnectorHealthWorker(registry, apply_port)

    checked = worker.run_once()

    assert checked == 2
    assert set(apply_port.calls) == {("c-active", True), ("c-degraded", False)}


def test_run_once_skips_disabled_and_deprecated_connectors() -> None:
    connectors = [
        _FakeConnector("c-disabled", ConnectorHealthStatus.DISABLED), _FakeConnector("c-deprecated", ConnectorHealthStatus.DEPRECATED),
    ]
    registry = _StubConnectorRegistryPort(connectors, adapters={})
    apply_port = _StubApplyHealthCheckPort()
    worker = ConnectorHealthWorker(registry, apply_port)

    checked = worker.run_once()

    assert checked == 0
    assert apply_port.calls == []


def test_run_once_continues_past_a_single_connector_failure() -> None:
    connectors = [
        _FakeConnector("c-1", ConnectorHealthStatus.ACTIVE), _FakeConnector("c-2", ConnectorHealthStatus.ACTIVE),
    ]
    adapters = {"c-1": _FakeAdapter(healthy=True), "c-2": _FakeAdapter(healthy=True)}
    registry = _StubConnectorRegistryPort(connectors, adapters)

    class _RaisingApplyHealthCheckPort(_StubApplyHealthCheckPort):
        def apply_health_check_result(self, connector_id: str, healthy: bool, correlation_id: str):
            super().apply_health_check_result(connector_id, healthy, correlation_id)
            if connector_id == "c-1":
                raise RuntimeError("boom")
            return None

    apply_port = _RaisingApplyHealthCheckPort()
    worker = ConnectorHealthWorker(registry, apply_port)

    checked = worker.run_once()

    assert checked == 2
    assert set(apply_port.calls) == {("c-1", True), ("c-2", True)}
