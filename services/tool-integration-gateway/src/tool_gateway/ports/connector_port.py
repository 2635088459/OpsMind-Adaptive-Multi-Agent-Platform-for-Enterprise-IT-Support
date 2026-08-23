"""13-package-and-class-design §"ConnectorPort": "Every connector must
implement: validate_input, invoke, reconcile, cancel, health_check." Also houses
``ConnectorRegistryPort`` — the port behind the "Main Classes" §"ConnectorRegistry"
("Registers connector manifests, resolves versions, and maps capabilities to
connectors"), which 13-package-and-class-design places under
``adapters/connectors/registry.py`` without naming a matching port file; kept here
rather than in ``storage_port.py`` because it is conceptually the connector SDK's
own directory lookup, not a persistence concern.
"""

from __future__ import annotations

from typing import Protocol

from tool_gateway.domain.connector import ToolConnector
from tool_gateway.domain.values import ConnectorInvocationSpec, ExecutionOutcome


class ConnectorPort(Protocol):
    """The SDK contract every concrete connector adapter (built-in or
    third-party) must satisfy. One instance per registered ``ToolConnector``.
    """

    def validate_input(self, spec: ConnectorInvocationSpec) -> None:
        """Raises on schema/input violation. Never performs the external call."""
        ...

    def invoke(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome:
        """Performs the external side effect (or read). Must never be called
        from inside a database transaction (domain-rules §"Forbidden": "Executing
        external connector calls inside a database transaction.").
        """
        ...

    def reconcile(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome:
        """04-use-cases UC-TG-005: queries the external system/connector status
        endpoint to resolve a TIMED_OUT/PARTIAL_SIDE_EFFECT attempt.
        """
        ...

    def cancel(self, spec: ConnectorInvocationSpec) -> None:
        """04-use-cases UC-TG-006 step 4: "Gateway calls connector cancel hook.\""""
        ...

    def health_check(self) -> bool:
        """Returns True when the connector should be considered ACTIVE-capable."""
        ...


class ConnectorRegistryPort(Protocol):
    """13-package-and-class-design §"ConnectorRegistry": registers manifests,
    resolves capability -> connector, and hands out the matching ConnectorPort
    instance to invoke.
    """

    def register(self, connector: ToolConnector, adapter: ConnectorPort) -> ToolConnector:
        """04-use-cases UC-TG-007 step 3: "Gateway persists connector registry
        version."
        """
        ...

    def find_by_capability(self, capability_name: str) -> ToolConnector | None:
        """01-domain-model §"Capability": "Gateway decides which connector
        implements it." Only ACTIVE-schedulable connectors are ever returned —
        03-state-machine §"Connector Health State Machine": "Scheduling may
        select only ACTIVE connectors."
        """
        ...

    def find_by_id(self, connector_id: object) -> ToolConnector | None: ...

    def get_adapter(self, connector_id: object) -> ConnectorPort:
        """Returns the concrete ConnectorPort implementation for an already
        resolved connector id.
        """
        ...

    def save(self, connector: ToolConnector) -> ToolConnector:
        """Persists a connector health-status transition (degrade/reactivate/
        disable/deprecate).
        """
        ...

    def list_all(self) -> list[ToolConnector]: ...
