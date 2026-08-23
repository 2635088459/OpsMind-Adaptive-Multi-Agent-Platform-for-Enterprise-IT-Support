"""SPEC-TG-016 "Retry Policy And Retry Scheduling" 04-use-cases UC-TG-004 steps
3-4: "Gateway creates the next attempt based on retry policy. If max attempts
are reached, ToolRequest enters TERMINAL_FAILED." Shared by
``execute_tool_request``'s own retryable-FAILED branch and
``reconcile_execution``'s own confirmed-failure branch (UC-TG-005 step 5: "If
failure is confirmed and retry is allowed, a new attempt is created.") — both
call sites need the exact same "is this attempt retryable, and are attempts
remaining" decision, just triggered from two different outcomes (a direct
connector failure vs. a reconciled one).
"""

from __future__ import annotations

from datetime import datetime, timedelta

from tool_gateway.domain.connector import ToolConnector


def is_retry_allowed(connector: ToolConnector, attempt_number: int, outcome_retryable: bool) -> bool:
    """``outcome_retryable`` is the connector's own per-outcome classification
    (``ExecutionOutcome.retryable`` — e.g. a permission error is never
    retryable regardless of attempts remaining); ``attempt_number`` is the
    attempt that just failed, so a retry is allowed only while it is strictly
    below the connector's own ``RetryPolicy.max_attempts``.
    """

    return outcome_retryable and attempt_number < connector.retry_policy.max_attempts


def compute_retry_not_before(connector: ToolConnector, now: datetime) -> datetime:
    """09-concurrency-and-idempotency names no literal backoff formula beyond
    the connector manifest's own ``RetryPolicy.backoff_seconds`` — a flat delay
    from the failure, not exponential, matching the field's own singular name
    (no ``backoff_multiplier``/``max_backoff`` field exists in 01-domain-model's
    own ``RetryPolicy`` shape to build anything more elaborate from).
    """

    return now + timedelta(seconds=connector.retry_policy.backoff_seconds)
