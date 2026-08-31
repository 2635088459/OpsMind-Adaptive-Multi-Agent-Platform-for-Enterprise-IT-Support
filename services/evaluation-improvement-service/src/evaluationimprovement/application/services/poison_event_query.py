"""Not among 13-package-and-class-design's ten named services — added the same
pragmatic way AuditRecordQueryService was: SPEC-EI-035 (langsmith-grader-outbox-
failure-recovery) / 05-api-contracts §"管理 API" `GET /evaluation/poison-events`
needs a real read surface, since a persisted-but-inaccessible poison-event table is
of no operational value (10-failure-handling step 4: "支持 admin replay" starts with
an operator being able to see what needs fixing).
"""

from __future__ import annotations

from evaluationimprovement.application.ports_out import PoisonEventRepository
from evaluationimprovement.application.records import PoisonEventRecord


class PoisonEventQueryService:
    def __init__(self, poison_event_repository: PoisonEventRepository) -> None:
        self._poison_event_repository = poison_event_repository

    def list_poison_events(self, limit: int) -> list[PoisonEventRecord]:
        return self._poison_event_repository.find_all(limit)
