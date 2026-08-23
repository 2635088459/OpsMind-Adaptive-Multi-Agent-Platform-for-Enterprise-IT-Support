"""01-domain-model §"ToolResultEnvelope": "All connector output must be
normalized into a Tool Result Envelope." INV-TG-007: "tool.completed.v1 carries
only summary, redacted structured output, evidence refs, and error metadata by
default. Raw output can be read only through controlled storage references." —
note there is deliberately no field here for raw output *content*, only
``raw_output_ref`` (a storage reference); domain-rules §"Forbidden": "Writing
connector raw output directly into Memory Knowledge" is structurally unrepresentable
by this shape, not merely policy.
"""

from __future__ import annotations

from dataclasses import dataclass

from tool_gateway.domain.enums import RedactionStatus, ResultStatus
from tool_gateway.domain.errors import ResultEnvelopeMissingSummaryException
from tool_gateway.domain.ids import ResultEnvelopeId, ToolExecutionId


@dataclass(frozen=True, slots=True)
class ToolResultEnvelope:
    """01-domain-model §"ToolResultEnvelope" field list, transcribed 1:1."""

    result_envelope_id: ResultEnvelopeId
    execution_id: ToolExecutionId
    status: ResultStatus
    summary: str
    structured_output: dict
    raw_output_ref: str | None
    redaction_status: RedactionStatus
    evidence_refs: tuple[str, ...]
    external_resource_refs: tuple[str, ...]
    error_code: str | None
    retryable: bool

    @staticmethod
    def create(
        result_envelope_id: ResultEnvelopeId,
        execution_id: ToolExecutionId,
        status: ResultStatus,
        summary: str,
        structured_output: dict,
        redaction_status: RedactionStatus,
        raw_output_ref: str | None = None,
        evidence_refs: tuple[str, ...] = (),
        external_resource_refs: tuple[str, ...] = (),
        error_code: str | None = None,
        retryable: bool = False,
    ) -> "ToolResultEnvelope":
        if not summary or not summary.strip():
            raise ResultEnvelopeMissingSummaryException()
        return ToolResultEnvelope(
            result_envelope_id=result_envelope_id, execution_id=execution_id, status=status, summary=summary,
            structured_output=structured_output, raw_output_ref=raw_output_ref, redaction_status=redaction_status,
            evidence_refs=evidence_refs, external_resource_refs=external_resource_refs, error_code=error_code,
            retryable=retryable,
        )
