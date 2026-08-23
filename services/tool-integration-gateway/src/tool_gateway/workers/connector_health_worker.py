"""13-package-and-class-design §"workers/connector_health_worker.py":
03-state-machine §"Connector Health State Machine". Probes every registered
connector's ``ConnectorPort.health_check()`` and drives ACTIVE/DEGRADED
transitions via ``RegisterConnectorUseCase.apply_health_check_result()`` —
never mutates ``ConnectorRegistryPort`` directly (that would bypass the
audit/outbox trail ``apply_health_check_result`` owns; see
``application.register_connector`` module docstring), mirroring every other
worker's own "query via a read port, mutate only through a use-case port"
shape (``ExecutionWorker``, ``ReconciliationWorker``). Manual admin lifecycle
(disable/enable, deprecation) is phase-07 scope (00-implementation-roadmap
SPEC-TG-026~029 "Observability Audit Admin"); this worker only ever drives the
*automatic* half of the ladder — ACTIVE<->DEGRADED, and (since SPEC-TG-030)
DEGRADED->DISABLED once consecutive failures cross
``settings.connector_disable_after_failures``. DEPRECATED and any
DISABLED->ACTIVE recovery stay purely human/admin-driven; a DISABLED
connector is skipped by ``run_once()``'s own health_status filter below, never
auto-reactivated.
"""

from __future__ import annotations

import logging
import time
import uuid

from tool_gateway.application.ports_in import RegisterConnectorUseCase
from tool_gateway.domain.enums import ConnectorHealthStatus
from tool_gateway.ports.connector_port import ConnectorRegistryPort

logger = logging.getLogger("tool_gateway.workers.connector_health")


class ConnectorHealthWorker:
    def __init__(self, connector_registry_port: ConnectorRegistryPort, apply_health_check_port: RegisterConnectorUseCase) -> None:
        self._connector_registry_port = connector_registry_port
        self._apply_health_check_port = apply_health_check_port

    def run_once(self) -> int:
        checked = 0
        for connector in self._connector_registry_port.list_all():
            if connector.health_status not in (ConnectorHealthStatus.ACTIVE, ConnectorHealthStatus.DEGRADED):
                continue
            adapter = self._connector_registry_port.get_adapter(connector.connector_id)
            healthy = adapter.health_check()
            checked += 1
            try:
                self._apply_health_check_port.apply_health_check_result(
                    connector.connector_id, healthy, correlation_id=str(uuid.uuid4()),
                )
            except Exception:
                logger.exception("connector health worker failed to apply health check result for %s", connector.connector_id)
        return checked

    def run_forever(self, poll_interval_seconds: float = 30.0) -> None:  # pragma: no cover - real deployment loop
        while True:
            self.run_once()
            time.sleep(poll_interval_seconds)
