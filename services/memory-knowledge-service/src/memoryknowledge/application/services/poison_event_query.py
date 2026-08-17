"""13-package-and-class-design §"Application Layer" analogue: PoisonEventQueryService,
the sole implementation of PoisonEventQueryPort. SPEC-MK-029 10-failure-handling
§"Poison Event" step 4: "等待人工修复后 replay" — a human needs to see what is currently
poisoned before fixing and replaying it (by resending the corrected event under the
same eventId to its original endpoint — this domain never built a separate automated
"replay" trigger, mirroring agent-runtime-service's own SPEC-ARO-031 precedent exactly:
its own admin API only ever adds "mark quarantined," never a bespoke re-dispatch
endpoint). A plain read, like AuditRecordQueryService: no domain call, no write, no
idempotency guard.

05-api-contracts §"Admin API": "mark poison event quarantined" adds mark_quarantined()
— also the sole implementation of PoisonEventCommandPort. Kept on this same class
rather than a separate service: it depends on nothing list_poison_events() doesn't
already have (the same PoisonEventRepository), the same precedent
DispatchOutboxEventsService already established for "one class mixing a read-ish scan
with a write" in this codebase.
"""

from __future__ import annotations

import uuid

from memoryknowledge.application.exceptions import PoisonEventNotFoundException
from memoryknowledge.application.ports_out import ClockPort, PoisonEventRepository, RedactionPolicyPort
from memoryknowledge.application.views import PoisonEventView


class PoisonEventQueryService:
    def __init__(self, poison_event_repository: PoisonEventRepository, redaction_policy_port: RedactionPolicyPort, clock: ClockPort) -> None:
        self._poison_event_repository = poison_event_repository
        self._redaction_policy_port = redaction_policy_port
        self._clock = clock

    def list_poison_events(self, limit: int) -> list[PoisonEventView]:
        records = self._poison_event_repository.find_all(limit)
        return [self._to_view(record) for record in records]

    def mark_quarantined(self, id: uuid.UUID) -> PoisonEventView:
        existing = self._poison_event_repository.find_by_id(id)
        if existing is None:
            raise PoisonEventNotFoundException(id)

        self._poison_event_repository.mark_quarantined(id, self._clock.now())

        updated = self._poison_event_repository.find_by_id(id)
        return self._to_view(updated)

    def _to_view(self, record) -> PoisonEventView:
        # 11-security §"Data Protection": a poisoned delivery is by definition
        # unvalidated content that could carry anything — redacted the same way every
        # other outward-facing payload in this codebase already is, via the existing
        # RedactionPolicyPort rather than a bespoke second regex (see PoisonEventView's
        # own docstring).
        redacted_payload, _report = self._redaction_policy_port.redact(record.payload)
        return PoisonEventView.from_record(record, redacted_payload)
