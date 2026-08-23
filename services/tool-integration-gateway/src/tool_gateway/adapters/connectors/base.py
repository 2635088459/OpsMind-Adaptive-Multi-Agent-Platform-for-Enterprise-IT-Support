"""13-package-and-class-design §"adapters/connectors/base.py": shared helper
base class for concrete ``ConnectorPort`` implementations — every real connector
(built-in or third-party, phase-03 scope) can subclass this instead of
re-implementing the trivial ``validate_input``/``health_check`` defaults.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from tool_gateway.domain.values import ConnectorInvocationSpec, ExecutionOutcome


class BaseConnector(ABC):
    """Default ``validate_input``: accepts anything (subclasses override for
    real schema checks — phase-03 scope). Default ``health_check``: always
    healthy (subclasses override to probe a real backend).
    """

    def validate_input(self, spec: ConnectorInvocationSpec) -> None:
        return None

    @abstractmethod
    def invoke(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome: ...

    def reconcile(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome:
        """Default reconciliation re-invokes — safe only for read-only/
        naturally-idempotent connectors; a real mutating connector must
        override this with a genuine status-lookup call (phase-04 scope).
        """
        return self.invoke(spec)

    def cancel(self, spec: ConnectorInvocationSpec) -> None:
        return None

    def health_check(self) -> bool:
        return True
