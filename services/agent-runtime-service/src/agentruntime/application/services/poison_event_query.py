"""13-package-and-class-design §"Application Layer": PoisonEventQueryService, the sole
implementation of PoisonEventQueryPort. SPEC-ARO-024 10-failure-handling §"Poison Event"
step 4: "等待人工修复后 replay" — a human needs to be able to see what is currently
poisoned before fixing and replaying it. A plain read, like WorkflowQueryService: no
domain call, no write, no idempotency guard.

SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined" adds
mark_quarantined() — also the sole implementation of PoisonEventCommandPort. Kept on
this same class rather than a separate service: it depends on nothing
list_poison_events() doesn't already have (the same PoisonEventRepository), and
DispatchOutboxEventsService already established the precedent of one class mixing a
read-ish scan with a write within this codebase.
"""

from __future__ import annotations

import uuid

from agentruntime.application.exceptions import PoisonEventNotFoundException
from agentruntime.application.ports_out import ClockPort, PoisonEventRepository
from agentruntime.application.views import PoisonEventView


class PoisonEventQueryService:
    def __init__(self, poison_event_repository: PoisonEventRepository, clock: ClockPort) -> None:
        self._poison_event_repository = poison_event_repository
        self._clock = clock

    def list_poison_events(self, limit: int) -> list[PoisonEventView]:
        records = self._poison_event_repository.find_all(limit)
        return [PoisonEventView.from_record(record) for record in records]

    def mark_quarantined(self, id: uuid.UUID) -> PoisonEventView:
        existing = self._poison_event_repository.find_by_id(id)
        if existing is None:
            raise PoisonEventNotFoundException(id)

        self._poison_event_repository.mark_quarantined(id, self._clock.now())

        updated = self._poison_event_repository.find_by_id(id)
        return PoisonEventView.from_record(updated)
