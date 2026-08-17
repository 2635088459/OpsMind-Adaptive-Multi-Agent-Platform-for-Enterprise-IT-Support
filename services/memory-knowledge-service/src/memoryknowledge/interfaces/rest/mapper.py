"""Mapping boundary for the Runtime-facing REST surface — only ever builds
application-layer commands/DTOs from wire schemas and back, never leaks a
memoryknowledge.domain type into the wire shape's own field types beyond plain
str/UUID/float (enforced by tests/architecture, mirroring the Java siblings'
LayerDependencyTest).
"""

from __future__ import annotations

from uuid import UUID

from memoryknowledge.application.commands import (
    QueryWorkingMemoryCommand,
    RejectHypothesisInput,
    SearchMemoryCommand,
    ToolEvidenceRefInput,
    UpdateWorkingMemoryCommand,
)
from memoryknowledge.application.views import SearchResultView, WorkingMemoryView
from memoryknowledge.domain.enums import MemoryType
from memoryknowledge.domain.ids import CorrelationId, TicketCycleId, TicketId, WorkflowInstanceId, WorkingMemoryId
from memoryknowledge.domain.values import AccessScope
from memoryknowledge.domain.working_memory import derive_working_memory_id as _derive_working_memory_id
from memoryknowledge.interfaces.rest.schemas import (
    GraphPathResponse,
    ProvenanceResponse,
    RejectedHypothesisResponse,
    SearchRequest,
    SearchResponse,
    SearchResultItemResponse,
    ToolEvidenceRefResponse,
    UpdateWorkingMemoryRequest,
    WorkingMemoryResponse,
)


def to_search_command(request: SearchRequest) -> SearchMemoryCommand:
    return SearchMemoryCommand(
        query=request.query, requester_type=request.requester_type, requester_id=request.requester_id,
        access_scope=AccessScope(
            tenant=request.access_scope.tenant, role=request.access_scope.role, classification=request.access_scope.classification,
            application=request.access_scope.application, queue=request.access_scope.queue,
        ),
        correlation_id=CorrelationId(request.correlation_id),
        ticket_id=TicketId(request.ticket_id) if request.ticket_id else None,
        ticket_cycle_id=TicketCycleId(request.ticket_cycle_id) if request.ticket_cycle_id else None,
        workflow_instance_id=WorkflowInstanceId(request.workflow_instance_id) if request.workflow_instance_id else None,
        memory_types=tuple(MemoryType[name] for name in request.filters.memory_types),
        max_results=request.filters.max_results, include_graph_paths=request.filters.include_graph_paths,
        max_graph_depth=request.filters.max_graph_depth,
    )


def to_search_response(view: SearchResultView) -> SearchResponse:
    return SearchResponse(
        retrieval_id=view.retrieval_id.value, degraded=view.degraded, degraded_reason=view.degraded_reason,
        graph_degraded=view.graph_degraded,
        results=[
            SearchResultItemResponse(
                result_type=item.result_type, source_id=item.source_id, source_version=item.source_version,
                snippet=item.snippet, score=item.score,
                provenance=ProvenanceResponse(
                    source_type=item.provenance.source_type, source_ref=item.provenance.source_ref, redacted=item.provenance.redacted,
                ),
                graph_paths=[
                    GraphPathResponse(nodes=list(path.node_ids), edges=list(path.edge_ids), path_score=path.path_score, explanation=path.explanation)
                    for path in item.graph_paths
                ],
            )
            for item in view.results
        ],
    )


def to_update_working_memory_command(request: UpdateWorkingMemoryRequest) -> UpdateWorkingMemoryCommand:
    return UpdateWorkingMemoryCommand(
        ticket_id=TicketId(request.ticket_id), ticket_cycle_id=TicketCycleId(request.ticket_cycle_id),
        workflow_instance_id=WorkflowInstanceId(request.workflow_instance_id), expected_version=request.expected_version,
        updated_by=request.updated_by, correlation_id=CorrelationId(request.correlation_id),
        add_facts=tuple(request.add_facts), add_hypotheses=tuple(request.add_hypotheses),
        reject_hypotheses=tuple(RejectHypothesisInput(h.hypothesis, h.reason) for h in request.reject_hypotheses),
        complete_tasks=tuple(request.complete_tasks), add_pending_tasks=tuple(request.add_pending_tasks),
        add_tool_evidence_refs=tuple(
            ToolEvidenceRefInput(t.tool_request_id, t.summary, t.status, t.evidence_hash) for t in request.add_tool_evidence_refs
        ),
        add_approval_decision_refs=tuple(request.add_approval_decision_refs), context_summary=request.context_summary,
    )


def to_working_memory_response(view: WorkingMemoryView) -> WorkingMemoryResponse:
    return WorkingMemoryResponse(
        working_memory_id=view.working_memory_id.value, version=view.version, status=view.status.name,
        facts=list(view.facts), hypotheses=list(view.hypotheses),
        rejected_hypotheses=[
            RejectedHypothesisResponse(hypothesis=r.hypothesis, reason=r.reason, rejected_at=r.rejected_at) for r in view.rejected_hypotheses
        ],
        completed_tasks=list(view.completed_tasks), pending_tasks=list(view.pending_tasks),
        tool_evidence_refs=[
            ToolEvidenceRefResponse(tool_request_id=t.tool_request_id, summary=t.summary, status=t.status, evidence_hash=t.evidence_hash)
            for t in view.tool_evidence_refs
        ],
        approval_decision_refs=list(view.approval_decision_refs),
        context_summary=view.context_summary, updated_at=view.updated_at,
    )


def derive_working_memory_id(ticket_id: UUID, ticket_cycle_id: UUID, workflow_instance_id: UUID) -> UUID:
    return _derive_working_memory_id(TicketId(ticket_id), TicketCycleId(ticket_cycle_id), WorkflowInstanceId(workflow_instance_id)).value


def to_query_working_memory_command(working_memory_id: UUID, correlation_id: UUID) -> QueryWorkingMemoryCommand:
    return QueryWorkingMemoryCommand(working_memory_id=WorkingMemoryId(working_memory_id), correlation_id=CorrelationId(correlation_id))
