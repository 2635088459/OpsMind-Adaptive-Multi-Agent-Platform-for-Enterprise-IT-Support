"""13-package-and-class-design §"adapters/events/rabbitmq_consumer.py": the real
RabbitMQ consumer for ``approval.granted.v1``/``approval.denied.v1``/
``policy.rule.changed.v1`` and other cross-domain events. SPEC-TG-009 built the
full application-layer consumption logic (dedup, idempotent-skip, linkage-
match — ``application.approve_tool_request.ApproveToolRequestService.
consume_approval_decision``, ``application.consume_policy_rule_changed``) and
an HTTP seam that calls it (``api.event_routes``, mirroring memory-knowledge-
service's own ``interfaces/event/router.py`` "manual/ops trigger until a real
RabbitMQ async consumer exists" precedent) — only the actual broker
subscription loop (``basic_consume``, ack/nack, DLQ wiring) remains, deferred
to phase-06 SPEC-TG-022~025 "Cross Domain Contracts" (no domain-06 service
exists yet in this monorepo to actually publish these events against).
"""

from __future__ import annotations


class RabbitMqEventConsumer:
    def __init__(self, *_args: object, **_kwargs: object) -> None:
        raise NotImplementedError("the real RabbitMQ subscription loop is phase-06 scope (SPEC-TG-022~025 cross-domain contracts) — see this module's own docstring")
