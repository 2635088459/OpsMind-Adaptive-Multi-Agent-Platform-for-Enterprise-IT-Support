"""SPEC-TG-026 "Metrics Logs Traces": smoke coverage for ``ToolGatewayTelemetry``
— every instrument constructs and every recording method executes without
raising. Mirrors this platform's own established convention (memory-
knowledge-service/agent-runtime-service write no dedicated metric-value
assertions either — real behavior is exercised implicitly through the full
application-level test suite, which already runs every request through a
container wired with a real ``ToolGatewayTelemetry()``, per SPEC-TG-026's own
traceability entry). This file only guards against basic wiring mistakes
(wrong instrument/label names) a full-flow test wouldn't surface directly.
"""

from __future__ import annotations

from tool_gateway.application.telemetry import ToolGatewayTelemetry


def test_every_recording_method_executes_without_raising() -> None:
    telemetry = ToolGatewayTelemetry()

    telemetry.record_request_created()
    telemetry.record_request_completed("COMPLETED")
    telemetry.record_execution_latency(1.5, "kubernetes-connector", "kubernetes.getPodLogs", "COMPLETED")
    telemetry.record_approval_wait(30.0, "kubernetes.restartDeployment", "HIGH")
    telemetry.record_connector_error("kubernetes-connector", "UPSTREAM_5XX")
    telemetry.record_connector_error("kubernetes-connector", None)
    telemetry.record_connector_timeout("kubernetes-connector")
    telemetry.record_execution_retry("kubernetes-connector", "kubernetes.getPodLogs")
    telemetry.record_reconciliation("SUCCEEDED")
    telemetry.set_outbox_pending(3)
    telemetry.record_outbox_publish_failure()
    telemetry.record_credential_access("kubernetes-connector", "api-token")
    telemetry.record_redaction_failure()
