"""SPEC-EI-030 (ticket-runtime-evaluation-contract) / SPEC-EI-031 (memory-tool-
evidence-contract): ConsumeCrossDomainEventService — dedup, redaction, and the
funnel into CollectOnlineSampleService.
"""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from evaluationimprovement.application.commands import (
    ConsumeMemoryRetrievalCompletedCommand,
    ConsumeTicketReopenedCommand,
    ConsumeTicketResolvedCommand,
    ConsumeToolCompletedCommand,
    ConsumeWorkflowCompletedCommand,
    ConsumeWorkflowFailedCommand,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import OnlineSampleStatus

_NOW = datetime.now(UTC)


@pytest.mark.unit
def test_ticket_resolved_collects_a_sample_without_leaking_the_raw_summary(container: Container) -> None:
    applied = container.consume_cross_domain_event_service.consume_ticket_resolved(ConsumeTicketResolvedCommand(
        event_id="evt-1", ticket_id="ticket-1", resolution_code="RESOLVED_FIXED",
        resolution_summary="Reset the user's password, which contained their SSN in the notes field.",
        resolved_at=_NOW, correlation_id="corr-1",
    ))
    assert applied is True

    samples = container.online_sample_repository.find_queued(limit=10)
    assert len(samples) == 1
    sample = samples[0]
    assert sample.source_event_type == "ticket.resolved.v1"
    assert sample.source_trace_ref == "ticket-1"
    assert sample.redacted_context == {"resolutionCode": "RESOLVED_FIXED", "hasResolutionSummary": True}
    assert "SSN" not in str(sample.redacted_context)


@pytest.mark.unit
def test_a_redelivered_event_is_not_applied_twice(container: Container) -> None:
    command = ConsumeTicketReopenedCommand(
        event_id="evt-2", ticket_id="ticket-2", reopen_reason_code="UNRESOLVED_ISSUE", reopen_count=1,
        reopened_at=_NOW, correlation_id="corr-1",
    )
    first = container.consume_cross_domain_event_service.consume_ticket_reopened(command)
    second = container.consume_cross_domain_event_service.consume_ticket_reopened(command)
    assert first is True
    assert second is False
    assert len(container.online_sample_repository.find_queued(limit=10)) == 1


@pytest.mark.unit
def test_workflow_completed_and_failed_both_collect(container: Container) -> None:
    container.consume_cross_domain_event_service.consume_workflow_completed(ConsumeWorkflowCompletedCommand(
        event_id="evt-3", workflow_instance_id="wf-1", ticket_id="ticket-3", to_state="COMPLETED", workflow_version=1,
        occurred_at=_NOW, correlation_id="corr-1",
    ))
    container.consume_cross_domain_event_service.consume_workflow_failed(ConsumeWorkflowFailedCommand(
        event_id="evt-4", workflow_instance_id="wf-2", ticket_id="ticket-4", to_state="FAILED", workflow_version=1,
        failure_reason="agent task exceeded retry budget with sensitive details attached", occurred_at=_NOW,
        correlation_id="corr-1",
    ))
    samples = container.online_sample_repository.find_queued(limit=10)
    event_types = {s.source_event_type for s in samples}
    assert event_types == {"workflow.completed.v1", "workflow.failed.v1"}
    failed_sample = next(s for s in samples if s.source_event_type == "workflow.failed.v1")
    assert failed_sample.redacted_context["hasFailureReason"] is True
    assert "retry budget" not in str(failed_sample.redacted_context)


@pytest.mark.unit
def test_tool_completed_only_marks_evidence_redacted_when_gateway_confirms_it(container: Container) -> None:
    container.consume_cross_domain_event_service.consume_tool_completed(ConsumeToolCompletedCommand(
        event_id="evt-5", tool_request_id="tool-1", capability_name="reset_duo_enrollment", status="COMPLETED",
        redaction_status="REDACTED", error_code=None, occurred_at=_NOW, correlation_id="corr-1",
    ))
    container.consume_cross_domain_event_service.consume_tool_completed(ConsumeToolCompletedCommand(
        event_id="evt-6", tool_request_id="tool-2", capability_name="send_email", status="TERMINAL_FAILED",
        redaction_status=None, error_code="CONNECTOR_TIMEOUT", occurred_at=_NOW, correlation_id="corr-1",
    ))
    samples = {s.source_trace_ref: s for s in container.online_sample_repository.find_queued(limit=10)}
    assert samples["tool-1"].redacted_context["evidenceRedacted"] is True
    assert samples["tool-2"].redacted_context["evidenceRedacted"] is False
    assert samples["tool-2"].redacted_context["errorCode"] == "CONNECTOR_TIMEOUT"


@pytest.mark.unit
def test_memory_retrieval_completed_collects_a_sample(container: Container) -> None:
    applied = container.consume_cross_domain_event_service.consume_memory_retrieval_completed(ConsumeMemoryRetrievalCompletedCommand(
        event_id="evt-7", query_id="query-1", memory_type="EPISODIC", result_count=3, acl_scope_denied=False,
        occurred_at=_NOW, correlation_id="corr-1",
    ))
    assert applied is True
    samples = container.online_sample_repository.find_queued(limit=10)
    assert any(s.source_event_type == "memory.retrieval.completed.v1" and s.status == OnlineSampleStatus.QUEUED for s in samples)
