"""Not among 13-package-and-class-design's ten named services — added the same way
memory-knowledge-service's own AuditRecordQueryService was: 05-api-contracts
§"管理 API" `GET /evaluation/audit` needs a real read surface, and a persisted-but-
inaccessible audit trail is of limited operational value.
"""

from __future__ import annotations

from evaluationimprovement.application.ports_out import AuditRecordRepository
from evaluationimprovement.application.records import AuditRecordEntry


class AuditRecordQueryService:
    def __init__(self, audit_record_repository: AuditRecordRepository) -> None:
        self._audit_record_repository = audit_record_repository

    def list_audit_events(self, limit: int) -> list[AuditRecordEntry]:
        return self._audit_record_repository.find_recent(limit)
