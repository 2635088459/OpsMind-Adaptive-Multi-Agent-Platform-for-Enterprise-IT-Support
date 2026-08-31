from __future__ import annotations

from evaluationimprovement.application.records import AuditRecordEntry, GatePolicyConfig, PoisonEventRecord
from evaluationimprovement.application.views import DispatchReport, GraderDescriptor
from evaluationimprovement.interfaces.admin.schemas import (
    AuditRecordResponse,
    DispatchReportResponse,
    GatePolicyResponse,
    GraderResponse,
    PoisonEventResponse,
    UpsertGatePolicyRequest,
)


def to_audit_response(entry: AuditRecordEntry) -> AuditRecordResponse:
    return AuditRecordResponse(
        action=entry.action, resource_type=entry.resource_type, resource_id=entry.resource_id, actor=entry.actor,
        outcome=entry.outcome, correlation_id=entry.correlation_id, detail=entry.detail, occurred_at=entry.occurred_at,
    )


def to_gate_policy_response(config: GatePolicyConfig) -> GatePolicyResponse:
    return GatePolicyResponse(
        gate_policy=config.gate_policy, dimension_thresholds=config.dimension_thresholds,
        critical_case_required=config.critical_case_required, max_policy_violations=config.max_policy_violations,
        max_forbidden_tool_calls=config.max_forbidden_tool_calls, max_unauthorized_memory_access=config.max_unauthorized_memory_access,
    )


def to_gate_policy_config(gate_policy: str, request: UpsertGatePolicyRequest) -> GatePolicyConfig:
    return GatePolicyConfig(
        gate_policy=gate_policy, dimension_thresholds=request.dimension_thresholds,
        critical_case_required=request.critical_case_required, max_policy_violations=request.max_policy_violations,
        max_forbidden_tool_calls=request.max_forbidden_tool_calls,
        max_unauthorized_memory_access=request.max_unauthorized_memory_access,
    )


def to_grader_response(descriptor: GraderDescriptor) -> GraderResponse:
    return GraderResponse(
        name=descriptor.name, grader_type=descriptor.grader_type.value, dimension=descriptor.dimension.value,
        version=descriptor.version,
    )


def to_dispatch_report_response(report: DispatchReport) -> DispatchReportResponse:
    return DispatchReportResponse(dispatched=report.dispatched, failed=report.failed, dead_lettered=report.dead_lettered)


def to_poison_event_response(entry: PoisonEventRecord) -> PoisonEventResponse:
    return PoisonEventResponse(
        id=str(entry.id), event_id=entry.event_id, consumer_name=entry.consumer_name, event_type=entry.event_type,
        payload=entry.payload, error_message=entry.error_message, occurred_at=entry.occurred_at, recorded_at=entry.recorded_at,
    )
