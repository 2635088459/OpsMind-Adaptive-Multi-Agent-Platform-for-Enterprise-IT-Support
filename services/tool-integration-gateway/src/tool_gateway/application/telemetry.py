"""SPEC-TG-026 "Metrics Logs Traces" 12-observability §"Metrics": the exact
12 named metrics that section lists, centralized in one class rather than
scattered ``meter.create_counter()`` calls across every service file —
mirrors memory-knowledge-service's own ``application/telemetry.py``
(``MemoryTelemetry``) and agent-runtime-service's own ``RuntimeTelemetry``
exactly, including their shared low-cardinality-labels-only rule: never an id
(toolRequestId, executionId, resultEnvelopeId) as a label value, only fixed,
small-vocabulary strings (connector *name*, not connectorId; capabilityName;
status/outcome/errorCode enums).

Uses the OpenTelemetry Metrics API directly (``metrics.get_meter(...)``), the
same "safe to call anywhere, real behavior wired once at the composition
root" shape this whole platform uses — ``ToolGatewayTelemetry`` itself holds
no OTel-SDK-specific logic, only instrument names and value recording through
the vendor-neutral API, so it stays a plain application-layer collaborator
(no import-linter violation: ``application`` depending on ``opentelemetry``,
a third-party library, is not the same as depending on ``tool_gateway.
adapters``).

``tool_outbox_pending_count`` is a true async Observable Gauge, not a
counter — it names a point-in-time depth, not something that accumulates
(mirrors memory-knowledge-service's own ``memory_outbox_backlog`` exactly,
including its "snapshot as of the last dispatch scan" caveat — see
``PublishOutboxService``'s own docstring for why: rows this call itself just
backed off, not a live ``COUNT(*)`` query).
"""

from __future__ import annotations

from opentelemetry import metrics
from opentelemetry.metrics import CallbackOptions, Observation

_meter = metrics.get_meter("tool_gateway")


class ToolGatewayTelemetry:
    def __init__(self) -> None:
        self._request_created = _meter.create_counter(
            "tool_request_created_total", description="Tool Requests accepted",
        )
        self._request_completed = _meter.create_counter(
            "tool_request_completed_total", description="Tool Requests that reached a final status",
        )
        self._execution_latency = _meter.create_histogram(
            "tool_execution_latency_seconds", unit="s", description="Wall-clock latency of one execution attempt",
        )
        self._approval_wait = _meter.create_histogram(
            "tool_approval_wait_seconds", unit="s", description="Time a Tool Request spent in WAITING_APPROVAL",
        )
        self._connector_error = _meter.create_counter(
            "tool_connector_error_total", description="Connector invocations that returned a FAILED outcome",
        )
        self._connector_timeout = _meter.create_counter(
            "tool_connector_timeout_total", description="Connector invocations that returned a TIMED_OUT outcome",
        )
        self._execution_retry = _meter.create_counter(
            "tool_execution_retry_total", description="Execution attempts that scheduled a retry",
        )
        self._reconciliation = _meter.create_counter(
            "tool_reconciliation_total", description="Reconciliation attempts, by outcome",
        )
        self._outbox_publish_failure = _meter.create_counter(
            "tool_outbox_publish_failure_total", description="Outbox publish attempts that failed",
        )
        self._credential_access = _meter.create_counter(
            "tool_credential_access_total", description="Credential bindings resolved for a connector invocation",
        )
        self._redaction_failure = _meter.create_counter(
            "tool_redaction_failure_total", description="Redaction calls that raised — the result was never published",
        )

        self._outbox_pending_value = 0
        _meter.create_observable_gauge(
            "tool_outbox_pending_count", callbacks=[self._read_outbox_pending],
            description="Outbox rows still dispatchable, as of the last publish scan",
        )

    def _read_outbox_pending(self, options: CallbackOptions):  # noqa: ARG002
        yield Observation(self._outbox_pending_value)

    def record_request_created(self) -> None:
        self._request_created.add(1)

    def record_request_completed(self, status: str) -> None:
        self._request_completed.add(1, {"status": status})

    def record_execution_latency(self, seconds: float, connector: str, capability: str, status: str) -> None:
        self._execution_latency.record(seconds, {"connector": connector, "capability": capability, "status": status})

    def record_approval_wait(self, seconds: float, capability: str, risk_level: str) -> None:
        self._approval_wait.record(seconds, {"capability": capability, "risk_level": risk_level})

    def record_connector_error(self, connector: str, error_code: str) -> None:
        self._connector_error.add(1, {"connector": connector, "error_code": error_code or "UNKNOWN"})

    def record_connector_timeout(self, connector: str) -> None:
        self._connector_timeout.add(1, {"connector": connector})

    def record_execution_retry(self, connector: str, capability: str) -> None:
        self._execution_retry.add(1, {"connector": connector, "capability": capability})

    def record_reconciliation(self, outcome: str) -> None:
        self._reconciliation.add(1, {"outcome": outcome})

    def set_outbox_pending(self, value: int) -> None:
        self._outbox_pending_value = value

    def record_outbox_publish_failure(self) -> None:
        self._outbox_publish_failure.add(1)

    def record_credential_access(self, connector: str, scope: str) -> None:
        self._credential_access.add(1, {"connector": connector, "scope": scope})

    def record_redaction_failure(self) -> None:
        self._redaction_failure.add(1)
