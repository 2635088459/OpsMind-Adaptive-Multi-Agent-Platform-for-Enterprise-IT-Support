"""SPEC-ARO-034 12-observability §"Metrics": the exact counter/gauge names this
section lists, centralized in one class rather than scattered `meter.create_counter()`
calls across every service file — mirrors the sibling ticket-workflow-service's own
`TicketTelemetry` (application/observability/TicketTelemetry.java): one place per
metric name, injected wherever it's needed, low-cardinality labels only (TicketTelemetry's
own explicit rule, carried over unchanged: never an id — workflowInstanceId, ticketId,
agentTaskId, correlationId — as a label value, only fixed, small-vocabulary strings like
event_type/failure_reason_category).

Uses the OpenTelemetry Metrics API directly (`metrics.get_meter(__name__)`), the same
"safe to call anywhere, real behavior wired once at the composition root" shape this
codebase already uses for the stdlib `logging` module — see
infrastructure/observability.py's own docstring for the full reasoning. RuntimeTelemetry
itself holds no OTel-SDK-specific logic; it only names instruments and records values
through the vendor-neutral API, so it stays a plain application-layer collaborator (no
import-linter violation), the same shape CommandIdempotencyGuard already established for
"a small, stateless helper class instantiated once and passed into every service that
needs it."
"""

from __future__ import annotations

from opentelemetry import metrics
from opentelemetry.metrics import CallbackOptions, Observation

_meter = metrics.get_meter("agentruntime")


class RuntimeTelemetry:
    def __init__(self) -> None:
        self._workflow_started = _meter.create_counter(
            "agent_runtime_workflow_started_total", description="Workflow Instances started"
        )
        self._workflow_completed = _meter.create_counter(
            "agent_runtime_workflow_completed_total", description="Workflow Instances completed"
        )
        self._workflow_failed = _meter.create_counter(
            "agent_runtime_workflow_failed_total", description="Workflow Instances failed"
        )
        self._workflow_paused = _meter.create_counter(
            "agent_runtime_workflow_paused_total", description="Workflow Instances paused"
        )
        self._workflow_recovered = _meter.create_counter(
            "agent_runtime_workflow_recovered_total", description="Recovery passes run against Workflow Instances"
        )
        self._workflow_duration = _meter.create_histogram(
            "agent_runtime_workflow_duration_seconds", unit="s", description="Workflow Instance wall-clock duration, start to terminal"
        )

        self._task_claimed = _meter.create_counter("agent_runtime_task_claimed_total", description="Agent Tasks claimed")
        self._task_completed = _meter.create_counter("agent_runtime_task_completed_total", description="Agent Tasks completed")
        self._task_failed = _meter.create_counter("agent_runtime_task_failed_total", description="Agent Tasks failed")
        self._task_retry = _meter.create_counter(
            "agent_runtime_task_retry_total", description="Agent Tasks retried after an expired lease"
        )
        self._task_lease_expired = _meter.create_counter(
            "agent_runtime_task_lease_expired_total", description="Agent Tasks whose lease was found expired by a recovery scan"
        )
        self._task_duration = _meter.create_histogram(
            "agent_runtime_task_duration_seconds", unit="s", description="Agent Task wall-clock duration, creation to terminal"
        )

        self._event_consumed = _meter.create_counter(
            "agent_runtime_event_consumed_total", description="Runtime events newly processed"
        )
        self._event_duplicate = _meter.create_counter(
            "agent_runtime_event_duplicate_total", description="Runtime events rejected as duplicate/stale"
        )
        # A true async Observable Gauge, not a counter: "agent_runtime_outbox_pending"
        # names a point-in-time depth, not something that accumulates. The reader
        # calls _read_outbox_pending() at each collection tick, which just returns
        # whatever set_outbox_pending() last cached — DispatchOutboxEventsService is
        # the only caller, updating it once per scan; RuntimeTelemetry itself has no
        # OutboxRepository dependency, so it stays a generic, repository-free
        # collaborator like every other method here.
        self._outbox_pending_value = 0
        _meter.create_observable_gauge(
            "agent_runtime_outbox_pending", callbacks=[self._read_outbox_pending],
            description="Outbox rows currently PENDING, as of the last dispatch scan",
        )
        self._outbox_publish_failed = _meter.create_counter(
            "agent_runtime_outbox_publish_failed_total", description="Outbox publish attempts that failed"
        )

    def _read_outbox_pending(self, options: CallbackOptions):  # noqa: ARG002
        yield Observation(self._outbox_pending_value)

    # Workflow metrics ----------------------------------------------------------------
    def record_workflow_started(self, workflow_type: str) -> None:
        self._workflow_started.add(1, {"workflow_type": workflow_type})

    def record_workflow_completed(self, workflow_type: str, duration_seconds: float) -> None:
        self._workflow_completed.add(1, {"workflow_type": workflow_type})
        self._workflow_duration.record(duration_seconds, {"workflow_type": workflow_type, "outcome": "completed"})

    def record_workflow_failed(self, workflow_type: str, duration_seconds: float | None = None) -> None:
        self._workflow_failed.add(1, {"workflow_type": workflow_type})
        if duration_seconds is not None:
            self._workflow_duration.record(duration_seconds, {"workflow_type": workflow_type, "outcome": "failed"})

    def record_workflow_paused(self, workflow_type: str) -> None:
        self._workflow_paused.add(1, {"workflow_type": workflow_type})

    def record_workflow_recovered(self, checkpoint_inconsistent: bool) -> None:
        self._workflow_recovered.add(1, {"checkpoint_inconsistent": str(checkpoint_inconsistent)})

    # Task metrics ----------------------------------------------------------------------
    def record_task_claimed(self) -> None:
        self._task_claimed.add(1)

    def record_task_completed(self, duration_seconds: float) -> None:
        self._task_completed.add(1)
        self._task_duration.record(duration_seconds, {"outcome": "completed"})

    def record_task_failed(self, duration_seconds: float) -> None:
        self._task_failed.add(1)
        self._task_duration.record(duration_seconds, {"outcome": "failed"})

    def record_task_retry(self) -> None:
        self._task_retry.add(1)

    def record_task_lease_expired(self) -> None:
        self._task_lease_expired.add(1)

    # Event metrics -----------------------------------------------------------------
    def record_event_consumed(self, event_type: str) -> None:
        self._event_consumed.add(1, {"event_type": event_type})

    def record_event_duplicate(self, event_type: str) -> None:
        self._event_duplicate.add(1, {"event_type": event_type})

    def set_outbox_pending(self, value: int) -> None:
        self._outbox_pending_value = value

    def record_outbox_publish_failed(self) -> None:
        self._outbox_publish_failed.add(1)
