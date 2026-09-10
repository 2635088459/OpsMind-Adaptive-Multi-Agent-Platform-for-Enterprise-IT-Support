"""HTTP fan-out + response classification.

A downstream response is one of three things to the relay:

* **ack**    — delivered, or permanently un-actionable (the target dedup'd it, the
               workflow/tool-request already moved on, the id is unknown). Retrying
               changes nothing. 2xx, and the "understood rejection" 4xx codes each
               target's own error handler documents (403/404/409/410/422).
* **retry**  — transient: 408/429/5xx or a transport error. Try again later.
* **dlq**    — a request the target refuses as malformed (400 and the other
               permanent 4xx). That is a relay transform bug — it must be visible in
               the DLQ, never silently ack'd.
"""

from __future__ import annotations

import enum
import logging
import time

import httpx
from opentelemetry import trace
from opentelemetry.propagate import inject

from event_relay import metrics
from event_relay.routing import Delivery

logger = logging.getLogger("event_relay.delivery")
tracer = trace.get_tracer("event_relay")

_ACK_STATUSES = frozenset({403, 404, 409, 410, 422})
_RETRY_STATUSES = frozenset({408, 425, 429})


class Outcome(enum.Enum):
    ACK = "ack"
    RETRY = "retry"
    DLQ = "dlq"


def classify(status_code: int) -> Outcome:
    if 200 <= status_code < 300:
        return Outcome.ACK
    if status_code in _ACK_STATUSES:
        return Outcome.ACK
    if status_code in _RETRY_STATUSES or 500 <= status_code < 600:
        return Outcome.RETRY
    return Outcome.DLQ


def deliver(
    client: httpx.Client,
    base_urls: dict[str, str],
    delivery: Delivery,
    *,
    event_id: str,
    max_attempts: int,
    backoff_seconds: float,
) -> Outcome:
    url = base_urls[delivery.target].rstrip("/") + delivery.path

    with tracer.start_as_current_span(
        f"event_relay.deliver {delivery.target}",
        kind=trace.SpanKind.CLIENT,
        attributes={"http.method": "POST", "http.route": delivery.path, "relay.target": delivery.target},
    ) as span:
        # The trace this delivery belongs to — same id as the consume span, and (when
        # the producer stamped a `traceparent` on the AMQP message) the same id the
        # producer and the downstream FastAPI service log. Printed on every delivery
        # line so the AMQP -> relay -> HTTP hop is traceable straight from the logs.
        trace_id = trace.format_trace_id(span.get_span_context().trace_id)

        headers = {"Content-Type": "application/json"}
        if delivery.correlation_id:
            headers["X-Correlation-Id"] = delivery.correlation_id
        # W3C traceparent (+ baggage) so the downstream FastAPI service continues this
        # trace instead of starting a detached one.
        inject(headers)

        outcome = Outcome.RETRY
        for attempt in range(1, max_attempts + 1):
            try:
                response = client.post(url, json=delivery.json_body, headers=headers)
            except httpx.RequestError as exc:
                logger.warning(
                    "action=relay_delivery_error event_id=%s trace_id=%s target=%s path=%s attempt=%s error=%s",
                    event_id, trace_id, delivery.target, delivery.path, attempt, type(exc).__name__,
                )
                outcome = Outcome.RETRY
            else:
                outcome = classify(response.status_code)
                span.set_attribute("http.status_code", response.status_code)
                logger.info(
                    "action=relay_delivery event_id=%s trace_id=%s target=%s path=%s attempt=%s status=%s outcome=%s body=%s",
                    event_id, trace_id, delivery.target, delivery.path, attempt, response.status_code, outcome.value,
                    _snippet(response.text),
                )

            if outcome is not Outcome.RETRY:
                break
            if attempt < max_attempts:
                time.sleep(backoff_seconds * attempt)

        span.set_attribute("relay.delivery_outcome", outcome.value)
        if outcome is Outcome.DLQ:
            span.set_status(trace.Status(trace.StatusCode.ERROR))
        metrics.record_delivery(delivery.target, outcome.value)
        return outcome


def combine(outcomes: list[Outcome]) -> Outcome:
    """Whole-message settlement across its (1-2) deliveries. `retry` wins over
    `dlq` wins over `ack`: a redelivery is safe (every target dedups by
    event_id), so if anything still needs retrying, retry the message; only
    dead-letter when nothing is retryable but something is genuinely malformed.
    """
    if any(o is Outcome.RETRY for o in outcomes):
        return Outcome.RETRY
    if any(o is Outcome.DLQ for o in outcomes):
        return Outcome.DLQ
    return Outcome.ACK


def _snippet(text: str, limit: int = 160) -> str:
    flat = " ".join(text.split())
    return flat[:limit]
