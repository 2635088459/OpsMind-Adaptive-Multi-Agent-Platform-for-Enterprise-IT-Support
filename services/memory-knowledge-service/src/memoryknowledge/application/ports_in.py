"""Input ports (13-package-and-class-design §"Ports": "Input ports"). One
typing.Protocol per named use case; each is implemented directly by the single
application service of the matching name in memoryknowledge.application.services.
"""

from __future__ import annotations

import uuid
from typing import Protocol

from memoryknowledge.application.commands import (
    ArchiveWorkingMemoryCommand,
    ConsumeTicketClosedCommand,
    ConsumeTicketResolvedCommand,
    ConsumeWorkflowCompletedCommand,
    ConsumeWorkflowFailedCommand,
    DeleteMemoryCommand,
    DeleteWorkingMemoryCommand,
    DeprecateMemoryCommand,
    ExpandKnowledgeGraphCommand,
    ExtractMemoryCandidateCommand,
    IngestKnowledgeDocumentCommand,
    PublishMemoryCommand,
    QueryWorkingMemoryCommand,
    ReindexDocumentCommand,
    RejectMemoryCandidateCommand,
    RetryDocumentIngestionCommand,
    SearchMemoryCommand,
    UpdateWorkingMemoryCommand,
    ValidateMemoryCandidateCommand,
)
from memoryknowledge.application.records import AuditRecordEntry
from memoryknowledge.application.views import (
    DeletionReport,
    DispatchReport,
    GraphExpansionView,
    KnowledgeDocumentView,
    MemoryCandidateView,
    MemoryVersionView,
    PoisonEventView,
    RecoveryScanReport,
    SearchResultView,
    WorkingMemoryView,
)


class UpdateWorkingMemoryUseCase(Protocol):
    """05-api-contracts: `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`."""

    def update_working_memory(self, command: UpdateWorkingMemoryCommand) -> WorkingMemoryView: ...


class WorkingMemoryQueryUseCase(Protocol):
    """SPEC-MK-006 05-api-contracts: `GET /internal/memory/v1/working-memory/
    {workingMemoryId}`. Not named in 13-package-and-class-design's own "Input ports"
    bullet list (that list predates this spec's query surface) — mirrors how
    WorkflowQueryPort is a sibling of WorkflowCommandPort in agent-runtime-service,
    kept separate from UpdateWorkingMemoryUseCase since nothing here writes, versions,
    or requires expected_version.
    """

    def find_working_memory(self, command: QueryWorkingMemoryCommand) -> WorkingMemoryView: ...


class WorkingMemoryLifecycleUseCase(Protocol):
    """SPEC-MK-006 03-state-machine §"Working Memory 状态": ACTIVE -> ARCHIVED / DELETED.
    Reached through the admin surface today (mirrors agent-runtime-service's own
    SPEC-ARO-004 WorkflowLifecyclePort precedent: an admin-triggered operation now,
    with automatic triggering via ticket-cycle-closed events deferred to a later
    cross-domain-contracts phase) — nothing yet decides *when* a cycle has ended.
    """

    def archive(self, command: ArchiveWorkingMemoryCommand) -> WorkingMemoryView: ...

    def delete(self, command: DeleteWorkingMemoryCommand) -> WorkingMemoryView: ...


class SearchMemoryUseCase(Protocol):
    """05-api-contracts: `POST /internal/memory/v1/search`. 05-api-contracts §"API 原则":
    "Search API 不改变 Memory 状态，只写 retrieval log."
    """

    def search(self, command: SearchMemoryCommand) -> SearchResultView: ...


class ExpandKnowledgeGraphUseCase(Protocol):
    def expand(self, command: ExpandKnowledgeGraphCommand) -> GraphExpansionView: ...


class IngestKnowledgeDocumentUseCase(Protocol):
    """05-api-contracts: `POST /internal/memory/v1/admin/documents`."""

    def ingest(self, command: IngestKnowledgeDocumentCommand) -> KnowledgeDocumentView: ...

    def retry(self, command: RetryDocumentIngestionCommand) -> KnowledgeDocumentView:
        """SPEC-MK-030 05-api-contracts §"Admin API": `POST .../documents/{documentId}/retry`."""
        ...

    def reindex(self, command: ReindexDocumentCommand) -> KnowledgeDocumentView:
        """SPEC-MK-030 05-api-contracts §"Admin API": `POST .../documents/{documentId}/reindex`."""
        ...


class ExtractMemoryCandidateUseCase(Protocol):
    def extract(self, command: ExtractMemoryCandidateCommand) -> MemoryCandidateView: ...


class TicketMemorySourceEventConsumerPort(Protocol):
    """SPEC-MK-010 06-event-contracts: consumed `ticket.resolved.v1` / `ticket.closed.v1`
    (02-ticket-workflow PUB-012/PUB-013). Implemented directly by
    ConsumeTicketMemorySourceEventService. Returns False for a duplicate delivery
    (already-processed eventId), True otherwise — mirrors agent-runtime-service's own
    TicketCreatedConsumerPort/TicketCycleConsumerPort return-value convention.
    """

    def consume_resolved(self, command: ConsumeTicketResolvedCommand) -> bool: ...

    def consume_closed(self, command: ConsumeTicketClosedCommand) -> bool: ...


class WorkflowMemorySourceEventConsumerPort(Protocol):
    """SPEC-MK-022 06-event-contracts: consumed `workflow.completed.v1` /
    `workflow.failed.v1` (03-agent-runtime-orchestration's own real published events).
    Implemented directly by ConsumeWorkflowMemorySourceEventService.
    """

    def consume_completed(self, command: ConsumeWorkflowCompletedCommand) -> bool: ...

    def consume_failed(self, command: ConsumeWorkflowFailedCommand) -> bool: ...


class ValidateMemoryCandidateUseCase(Protocol):
    """Drives EXTRACTED -> REDACTED -> VALIDATED -> {VALIDATED | DUPLICATE | CONFLICTING}
    (03-state-machine). reject() covers 05-api-contracts
    `POST .../candidates/{candidateId}/reject`.
    """

    def validate(self, command: ValidateMemoryCandidateCommand) -> MemoryCandidateView: ...

    def reject(self, command: RejectMemoryCandidateCommand) -> MemoryCandidateView: ...


class PublishMemoryUseCase(Protocol):
    """05-api-contracts `POST .../candidates/{candidateId}/approve` ("批准候选 memory 并触发
    publish").
    """

    def publish(self, command: PublishMemoryCommand) -> MemoryVersionView: ...


class ExecuteRetentionUseCase(Protocol):
    """05-api-contracts `POST .../memories/{memoryId}/deprecate` and
    `POST .../deletion-requests`.
    """

    def deprecate(self, command: DeprecateMemoryCommand) -> MemoryVersionView: ...

    def delete(self, command: DeleteMemoryCommand) -> DeletionReport: ...


class OutboxDispatchPort(Protocol):
    """SPEC-MK-003 08-transaction-and-outbox §"Outbox Publisher". Not named in
    13-package-and-class-design's "Input ports" bullet list (mirrors
    agent-runtime-service's own OutboxDispatchPort, also absent from that list there) —
    an operational surface, not a domain use case.
    """

    def dispatch_due_events(self, batch_size: int) -> DispatchReport: ...

    def replay_dead_letter(self, batch_size: int) -> DispatchReport:
        """SPEC-MK-003 08-transaction-and-outbox §"Outbox Publisher": "replay 必须幂等" —
        the manual/ops intervention OutboxRepository.requeue()'s own docstring names.
        """
        ...


class AuditRecordQueryPort(Protocol):
    """SPEC-MK-003 12-observability §"Audit Events" visibility. Implemented directly by
    AuditRecordQueryService. A persisted-but-inaccessible audit trail is of limited
    operational value.
    """

    def list_audit_events(self, limit: int) -> list[AuditRecordEntry]: ...


class PoisonEventQueryPort(Protocol):
    """SPEC-MK-029 10-failure-handling §"Poison Event" step 4's own admin visibility
    surface. Implemented directly by PoisonEventQueryService.
    """

    def list_poison_events(self, limit: int) -> list[PoisonEventView]: ...


class PoisonEventCommandPort(Protocol):
    """SPEC-MK-029 05-api-contracts §"Admin API": "mark poison event quarantined."
    Implemented directly by PoisonEventQueryService — kept as a separate Protocol from
    PoisonEventQueryPort so the pure-visibility surface stays undisturbed for any
    caller that only ever needs to list, mirroring agent-runtime-service's own
    PoisonEventQueryPort/PoisonEventCommandPort split.
    """

    def mark_quarantined(self, id: uuid.UUID) -> PoisonEventView: ...


class RecoveryPort(Protocol):
    """SPEC-MK-029 10-failure-handling §"Recovery Workers". Not named in
    13-package-and-class-design's own "Input ports" bullet list (mirrors
    OutboxDispatchPort's own absence there) — an operational surface, not a domain use
    case. Implemented directly by RecoverMemoryOperationsService.
    """

    def scan_and_recover_ingestion(self, batch_size: int) -> RecoveryScanReport:
        """10-failure-handling: "ingestion recovery：扫描 stuck document" /
        "embedding recovery：扫描 pending / failed retryable job" — one scan covers
        both bullets in this codebase's own architecture: IngestKnowledgeDocumentService
        runs parse→chunk→embed→graph→index synchronously in one call, so a document
        stuck anywhere between RECEIVED and INDEXED *is* the unrecovered
        embedding/ingestion job, not two independent failure surfaces.
        """
        ...

    def scan_and_recover_publish_graph(self, batch_size: int) -> RecoveryScanReport:
        """10-failure-handling: "graph recovery：扫描 graph extraction/upsert failed
        jobs" — an ACTIVE MemoryVersion missing its own MEMORY_VERSION graph node
        indicates a crash between PublishMemoryService's own version-save and its
        graph-upsert step; recovery re-runs the (idempotent, stable-key-deduped)
        upsert for it.
        """
        ...

    def scan_and_recover_retention(self, batch_size: int) -> RecoveryScanReport:
        """10-failure-handling: "retention recovery：扫描 partially applied deletion" —
        see GraphNodeRepository.find_by_type_and_status()'s own docstring for the
        detection shape this reuses.
        """
        ...
