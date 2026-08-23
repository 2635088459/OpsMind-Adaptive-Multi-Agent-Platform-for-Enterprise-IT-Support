"""13-package-and-class-design §"ConnectorRegistry": "Registers connector
manifests, resolves versions, and maps capabilities to connectors." Implements
``ports.connector_port.ConnectorRegistryPort`` by composing over an in-memory
``ConnectorRepository`` (the persisted manifest half) and an adapter table (the
runtime ``ConnectorPort`` instance half).
"""

from __future__ import annotations

import threading

from tool_gateway.adapters.db.repositories import InMemoryConnectorRepository
from tool_gateway.application.exceptions import ConnectorNotFoundException
from tool_gateway.domain.connector import ToolConnector
from tool_gateway.domain.enums import ConnectorHealthStatus
from tool_gateway.domain.ids import ConnectorId
from tool_gateway.ports.connector_port import ConnectorPort


class ConnectorRegistry:
    def __init__(self, connector_repository: InMemoryConnectorRepository) -> None:
        self._connector_repository = connector_repository
        self._lock = threading.Lock()
        self._adapters: dict[ConnectorId, ConnectorPort] = {}

    def register(self, connector: ToolConnector, adapter: ConnectorPort) -> ToolConnector:
        saved = self._connector_repository.save(connector)
        with self._lock:
            self._adapters[saved.connector_id] = adapter
        return saved

    def find_by_capability(self, capability_name: str) -> ToolConnector | None:
        # SPEC-TG-019: ``is_executable()`` admits an eligible DEGRADED
        # fallback alongside ACTIVE — an ACTIVE candidate is always preferred
        # over a DEGRADED one when both implement the same capability.
        candidates = [c for c in self._connector_repository.list_all() if c.supports_capability(capability_name) and c.is_executable()]
        if not candidates:
            return None
        candidates.sort(key=lambda c: c.health_status is not ConnectorHealthStatus.ACTIVE)
        return candidates[0]

    def find_by_id(self, connector_id: ConnectorId) -> ToolConnector | None:
        return self._connector_repository.find_by_id(connector_id)

    def get_adapter(self, connector_id: ConnectorId) -> ConnectorPort:
        with self._lock:
            adapter = self._adapters.get(connector_id)
        if adapter is None:
            raise ConnectorNotFoundException(connector_id)
        return adapter

    def save(self, connector: ToolConnector) -> ToolConnector:
        return self._connector_repository.save(connector)

    def list_all(self) -> list[ToolConnector]:
        return self._connector_repository.list_all()
