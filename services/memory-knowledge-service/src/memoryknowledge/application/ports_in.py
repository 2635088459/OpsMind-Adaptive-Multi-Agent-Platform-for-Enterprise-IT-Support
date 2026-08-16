"""Input ports (13-package-and-class-design §"Ports": "Input ports"). One
typing.Protocol per named use case; each is implemented directly by the single
application service of the matching name in memoryknowledge.application.services.
"""

from __future__ import annotations

from typing import Protocol

from memoryknowledge.application.commands import (
    DeleteMemoryCommand,
    DeprecateMemoryCommand,
    ExpandKnowledgeGraphCommand,
    ExtractMemoryCandidateCommand,
    IngestKnowledgeDocumentCommand,
    PublishMemoryCommand,
    RejectMemoryCandidateCommand,
    SearchMemoryCommand,
    UpdateWorkingMemoryCommand,
    ValidateMemoryCandidateCommand,
)
from memoryknowledge.application.views import (
    DeletionReport,
    DispatchReport,
    GraphExpansionView,
    KnowledgeDocumentView,
    MemoryCandidateView,
    MemoryVersionView,
    SearchResultView,
    WorkingMemoryView,
)


class UpdateWorkingMemoryUseCase(Protocol):
    """05-api-contracts: `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`."""

    def update_working_memory(self, command: UpdateWorkingMemoryCommand) -> WorkingMemoryView: ...


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


class ExtractMemoryCandidateUseCase(Protocol):
    def extract(self, command: ExtractMemoryCandidateCommand) -> MemoryCandidateView: ...


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
    """08-transaction-and-outbox (deferred detail to SPEC-MK-003) §"Outbox Publisher".
    Not named in 13-package-and-class-design's "Input ports" bullet list (mirrors
    agent-runtime-service's own OutboxDispatchPort, also absent from that list there) —
    an operational surface, not a domain use case.
    """

    def dispatch_due_events(self, batch_size: int) -> DispatchReport: ...
