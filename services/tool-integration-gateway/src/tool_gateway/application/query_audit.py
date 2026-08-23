"""SPEC-TG-027 "Audit Query And Admin Reporting" 12-observability
§"Audit Observability": "Audit query should support: all tool executions by
ticket; all execution attempts by workflow; failures and credential usage by
connector; tool requests by actor; execution results by approval request."
Not one of the seven ``application/`` filenames 13-package-and-class-design
literally lists — added the same way ``application.register_connector``/
``application.consume_policy_rule_changed`` extended that set for their own
admin/event surfaces.

"All execution attempts by workflow" and "execution results by approval
request" stay unimplemented here: no ``workflow_instance_id``/
``approval_request_id`` column exists on ``tool_audit_records`` (07-data-
model's own column list never named them, and threading them through every
``AuditRecorder.record()`` call site across the whole application layer —
beyond the bounded execute_tool_request/reconcile_execution scope
``tool_request_id``/``execution_id``/``connector_id`` already got — is a
larger change than this pass makes; a caller can still reach both indirectly
via ``find_by_ticket_id``/``find_by_actor_id`` today).
"""

from __future__ import annotations

from tool_gateway.application.views import AuditRecordView
from tool_gateway.ports.storage_port import AuditRecordRepository


class AuditQueryService:
    def __init__(self, audit_record_repository: AuditRecordRepository) -> None:
        self._audit_record_repository = audit_record_repository

    def find_recent(self, limit: int) -> list[AuditRecordView]:
        return [AuditRecordView.from_domain(e) for e in self._audit_record_repository.find_recent(limit)]

    def find_by_ticket_id(self, ticket_id: str, limit: int) -> list[AuditRecordView]:
        return [AuditRecordView.from_domain(e) for e in self._audit_record_repository.find_by_ticket_id(ticket_id, limit)]

    def find_by_actor_id(self, actor_id: str, limit: int) -> list[AuditRecordView]:
        return [AuditRecordView.from_domain(e) for e in self._audit_record_repository.find_by_actor_id(actor_id, limit)]

    def find_by_connector_id(self, connector_id: str, limit: int) -> list[AuditRecordView]:
        return [AuditRecordView.from_domain(e) for e in self._audit_record_repository.find_by_connector_id(connector_id, limit)]
