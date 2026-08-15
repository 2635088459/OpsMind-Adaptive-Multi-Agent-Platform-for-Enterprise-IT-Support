"""SPEC-ARO-034 12-observability §"Metrics". Deliberately smoke tests, not exact
exported-value assertions: OpenTelemetry's global MeterProvider can only be set once
per process (a second `metrics.set_meter_provider()` call is a documented no-op with a
warning) — since this whole pytest session runs in one process, and some other test
(e.g. tests/test_app.py's own `create_app()`) may already have installed the real
console-exporting MeterProvider before this file's own tests run, there is no reliable,
order-independent way to intercept RuntimeTelemetry's own exported data points here.
What every method must do, unconditionally, is complete without raising — the real
regression this suite guards against is a typo in an instrument name/label call
crashing the service that calls it.
"""

from __future__ import annotations

import pytest

from agentruntime.application.telemetry import RuntimeTelemetry

pytestmark = pytest.mark.unit


@pytest.fixture
def telemetry() -> RuntimeTelemetry:
    return RuntimeTelemetry()


def test_every_workflow_metric_method_is_callable_without_raising(telemetry: RuntimeTelemetry) -> None:
    telemetry.record_workflow_started("TICKET_TRIAGE")
    telemetry.record_workflow_completed("TICKET_TRIAGE", 12.5)
    telemetry.record_workflow_failed("TICKET_TRIAGE", 3.0)
    telemetry.record_workflow_failed("TICKET_TRIAGE")  # duration_seconds is optional
    telemetry.record_workflow_paused("TICKET_TRIAGE")
    telemetry.record_workflow_recovered(checkpoint_inconsistent=True)
    telemetry.record_workflow_recovered(checkpoint_inconsistent=False)


def test_every_task_metric_method_is_callable_without_raising(telemetry: RuntimeTelemetry) -> None:
    telemetry.record_task_claimed()
    telemetry.record_task_completed(4.2)
    telemetry.record_task_failed(1.1)
    telemetry.record_task_retry()
    telemetry.record_task_lease_expired()


def test_every_event_and_outbox_metric_method_is_callable_without_raising(telemetry: RuntimeTelemetry) -> None:
    telemetry.record_event_consumed("tool.completed.v1")
    telemetry.record_event_duplicate("tool.completed.v1")
    telemetry.set_outbox_pending(3)
    telemetry.set_outbox_pending(0)
    telemetry.record_outbox_publish_failed()


def test_multiple_instances_do_not_conflict(telemetry: RuntimeTelemetry) -> None:
    """Every application service gets its own RuntimeTelemetry instance in tests (see
    tests/support/telemetry.py) — constructing many must not itself raise, even though
    every instance registers instruments under the same fixed names.
    """
    another = RuntimeTelemetry()
    another.record_task_claimed()
