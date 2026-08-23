"""A real, working, deliberately trivial connector: echoes its input back as
the structured output. Not a production connector — the honest, labeled
placeholder every ``RegisterConnectorCommand`` is bound to until phase-03
(00-implementation-roadmap SPEC-TG-010~015 "Execution Worker And Connectors")
adds real per-connector adapter loading (Kubernetes/ServiceNow/Slack SDKs,
etc.), mirroring memory-knowledge-service's own "deterministic hash embedding
placeholder" convention: real logic, honestly documented scope, never silently
pretending to be more than it is.
"""

from __future__ import annotations

from tool_gateway.adapters.connectors.base import BaseConnector
from tool_gateway.domain.enums import ResultStatus
from tool_gateway.domain.values import ConnectorInvocationSpec, ExecutionOutcome


class EchoConnectorAdapter(BaseConnector):
    def invoke(self, spec: ConnectorInvocationSpec) -> ExecutionOutcome:
        return ExecutionOutcome(
            status=ResultStatus.SUCCESS, summary=f"echo connector invoked with operationKey={spec.operation_key}",
            structured_output={"echo": spec.input_payload}, raw_output=None, error_code=None, retryable=False,
        )
