"""13-package-and-class-design §"Application Layer": AuditRecordQueryService, the sole
implementation of AuditRecordQueryPort. SPEC-MK-003 12-observability §"Audit Events":
a plain read — no domain call, no write, no idempotency guard.
"""

from __future__ import annotations

from memoryknowledge.application.ports_out import AuditRecordRepository
from memoryknowledge.application.records import AuditRecordEntry


class AuditRecordQueryService:
    def __init__(self, audit_record_repository: AuditRecordRepository) -> None:
        self._audit_record_repository = audit_record_repository

    def list_audit_events(self, limit: int) -> list[AuditRecordEntry]:
        return self._audit_record_repository.find_recent(limit)
