"""13-package-and-class-design §"Interfaces": "Admin controller." SPEC-ARO-001
api-contract: "Admin APIs must record audit" — every recovery/dispatch request is
logged with the requesting actor before and after the operation, via this module's own
audit_logger. SPEC-ARO-034 12-observability §"Audit Events" adds the dedicated,
persisted audit-record port/table this module's own docstring once deferred (mirroring
the sibling Ticket Workflow service's AuditRecordPort) — AuditRecorder, injected into
the application services that call it, not this router directly; GET /audit-events
below is its own visibility surface.
"""

from __future__ import annotations

import logging
from uuid import UUID

from fastapi import APIRouter, Depends, Request

from agentruntime.application.ports_in import (
    AuditRecordQueryPort,
    LeaseRecoveryPort,
    OutboxDispatchPort,
    PoisonEventCommandPort,
    PoisonEventQueryPort,
    RecoveryPort,
    ToolDispatchPort,
    WorkflowLifecyclePort,
)
from agentruntime.container import (
    get_audit_record_query_port,
    get_lease_recovery_port,
    get_outbox_dispatch_port,
    get_poison_event_command_port,
    get_poison_event_query_port,
    get_recovery_port,
    get_tool_dispatch_port,
    get_workflow_lifecycle_port,
)
from agentruntime.interfaces.admin.mapper import (
    to_agent_task_response,
    to_audit_event_list_response,
    to_cancel_command,
    to_command,
    to_complete_command,
    to_dispatch_response,
    to_dispatch_tool_requests_response,
    to_fail_command,
    to_force_recover_command,
    to_lease_recovery_scan_response,
    to_poison_event_list_response,
    to_poison_event_response,
    to_response,
    to_retry_task_command,
    to_scan_response,
    to_workflow_instance_response,
)
from agentruntime.interfaces.admin.schemas import (
    AgentTaskResponse,
    AuditEventListResponse,
    CancelWorkflowRequest,
    CompleteWorkflowRequest,
    DispatchOutboxEventsRequest,
    DispatchReportResponse,
    DispatchToolRequestsRequest,
    DispatchToolRequestsReportResponse,
    FailWorkflowRequest,
    ForceRecoverWorkflowRequest,
    LeaseRecoveryScanRequest,
    LeaseRecoveryScanReportResponse,
    PoisonEventListResponse,
    PoisonEventResponse,
    RecoverWorkflowRequest,
    RecoveryReportResponse,
    RecoveryScanRequest,
    RecoveryScanReportResponse,
    ReplayDeadLetterRequest,
    RetryAgentTaskRequest,
    WorkflowInstanceResponse,
)

router = APIRouter(prefix="/internal/agent-runtime/v1/admin", tags=["admin"])
audit_logger = logging.getLogger("agentruntime.audit")


@router.post("/workflows/{workflow_instance_id}/recover", response_model=RecoveryReportResponse)
def recover_workflow(
    workflow_instance_id: UUID,
    request: Request,
    body: RecoverWorkflowRequest = RecoverWorkflowRequest(),
    port: RecoveryPort = Depends(get_recovery_port),
) -> RecoveryReportResponse:
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=recover_workflow_instance status=started workflow_instance_id=%s actor=%s", workflow_instance_id, actor)

    report = port.recover(to_command(workflow_instance_id, body))

    audit_logger.info(
        "action=recover_workflow_instance status=completed workflow_instance_id=%s actor=%s state=%s workflow_version=%s",
        workflow_instance_id, actor, report.state, report.workflow_version,
    )
    return to_response(report)


@router.post("/workflows/recovery-scan", response_model=RecoveryScanReportResponse)
def scan_and_recover_workflows(
    request: Request,
    body: RecoveryScanRequest = RecoveryScanRequest(),
    port: RecoveryPort = Depends(get_recovery_port),
) -> RecoveryScanReportResponse:
    """SPEC-ARO-028 10-failure-handling §"Runtime 崩溃后怎么恢复": "Recovery worker 周期性
    扫描非终态 Workflow Instance." Manual/ops trigger until a real scheduler exists,
    mirroring /admin/outbox/dispatch and /admin/tool-requests/dispatch.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=scan_and_recover_workflows status=started actor=%s batch_size=%s", actor, body.batch_size)

    report = port.scan_and_recover(body.batch_size)

    audit_logger.info(
        "action=scan_and_recover_workflows status=completed actor=%s scanned=%s checkpoint_inconsistent=%s",
        actor, report.scanned, report.checkpoint_inconsistent,
    )
    return to_scan_response(report)


@router.post("/workflows/{workflow_instance_id}/force-recover", response_model=RecoveryScanReportResponse)
def force_recover_workflow(
    workflow_instance_id: UUID,
    request: Request,
    body: ForceRecoverWorkflowRequest = ForceRecoverWorkflowRequest(),
    port: RecoveryPort = Depends(get_recovery_port),
) -> RecoveryScanReportResponse:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "force recover workflow" — the
    admin-triggered, single-instance counterpart to /workflows/recovery-scan, for an
    operator who has already identified one specific stuck instance and does not want
    to wait for the next scheduled sweep.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=force_recover_workflow status=started workflow_instance_id=%s actor=%s", workflow_instance_id, actor)

    report = port.force_recover(to_force_recover_command(workflow_instance_id))

    audit_logger.info(
        "action=force_recover_workflow status=completed workflow_instance_id=%s actor=%s checkpoint_inconsistent=%s",
        workflow_instance_id, actor, report.checkpoint_inconsistent,
    )
    return to_scan_response(report)


@router.post("/agent-tasks/lease-recovery-scan", response_model=LeaseRecoveryScanReportResponse)
def scan_and_recover_expired_leases(
    request: Request,
    body: LeaseRecoveryScanRequest = LeaseRecoveryScanRequest(),
    port: LeaseRecoveryPort = Depends(get_lease_recovery_port),
) -> LeaseRecoveryScanReportResponse:
    """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5: "对 CLAIMED/RUNNING
    且 lease 过期的 task 做 retry 或 stale 标记." Manual/ops trigger until a real scheduler
    exists, mirroring /admin/workflows/recovery-scan and /admin/outbox/dispatch.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=scan_and_recover_expired_leases status=started actor=%s batch_size=%s", actor, body.batch_size)

    report = port.scan_and_recover(body.batch_size)

    audit_logger.info(
        "action=scan_and_recover_expired_leases status=completed actor=%s scanned=%s retried=%s staled=%s",
        actor, report.scanned, report.retried, report.staled,
    )
    return to_lease_recovery_scan_response(report)


@router.post("/agent-tasks/{agent_task_id}/retry", response_model=AgentTaskResponse)
def retry_agent_task(
    agent_task_id: UUID,
    request: Request,
    body: RetryAgentTaskRequest = RetryAgentTaskRequest(),
    port: LeaseRecoveryPort = Depends(get_lease_recovery_port),
) -> AgentTaskResponse:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "retry failed task" — the
    admin-triggered, single-task counterpart to /agent-tasks/lease-recovery-scan, for an
    operator who has already identified a stuck CLAIMED/RUNNING task and does not want
    to wait for its lease to actually expire. Never reaches a terminal task
    (03-state-machine: FAILED_FINAL stays non-retryable).
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=retry_agent_task status=started agent_task_id=%s actor=%s", agent_task_id, actor)

    view = port.retry_task(to_retry_task_command(agent_task_id))

    audit_logger.info(
        "action=retry_agent_task status=completed agent_task_id=%s actor=%s state=%s", agent_task_id, actor, view.state,
    )
    return to_agent_task_response(view)


@router.post("/outbox/dispatch", response_model=DispatchReportResponse)
def dispatch_outbox_events(
    request: Request,
    body: DispatchOutboxEventsRequest = DispatchOutboxEventsRequest(),
    port: OutboxDispatchPort = Depends(get_outbox_dispatch_port),
) -> DispatchReportResponse:
    """08-transaction-and-outbox §"Outbox Publisher". Manual/ops trigger until phase-07
    (runtime-event-publishing) adds a real periodic scheduler.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=dispatch_outbox_events status=started actor=%s batch_size=%s", actor, body.batch_size)

    report = port.dispatch_due_events(body.batch_size)

    audit_logger.info(
        "action=dispatch_outbox_events status=completed actor=%s scanned=%s published=%s failed=%s dead_lettered=%s",
        actor, report.scanned, report.published, report.failed, report.dead_lettered,
    )
    return to_dispatch_response(report)


@router.post("/outbox/replay-dead-letter", response_model=DispatchReportResponse)
def replay_dead_letter_outbox_events(
    request: Request,
    body: ReplayDeadLetterRequest = ReplayDeadLetterRequest(),
    port: OutboxDispatchPort = Depends(get_outbox_dispatch_port),
) -> DispatchReportResponse:
    """SPEC-ARO-030 10-failure-handling §"Runtime 崩溃后怎么恢复" step 3: "重放未发布
    outbox" — the manual/ops intervention OutboxStatus.DEAD_LETTER's own docstring names
    ("requires manual/ops intervention"). Manual/ops trigger until a real scheduler
    exists, mirroring /admin/outbox/dispatch.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=replay_dead_letter_outbox_events status=started actor=%s batch_size=%s", actor, body.batch_size)

    report = port.replay_dead_letter(body.batch_size)

    audit_logger.info(
        "action=replay_dead_letter_outbox_events status=completed actor=%s scanned=%s published=%s failed=%s dead_lettered=%s",
        actor, report.scanned, report.published, report.failed, report.dead_lettered,
    )
    return to_dispatch_response(report)


@router.post("/tool-requests/dispatch", response_model=DispatchToolRequestsReportResponse)
def dispatch_tool_requests(
    request: Request,
    body: DispatchToolRequestsRequest = DispatchToolRequestsRequest(),
    port: ToolDispatchPort = Depends(get_tool_dispatch_port),
) -> DispatchToolRequestsReportResponse:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 6: "Tool
    Gateway 调用不能在事务内直接同步执行." Manual/ops trigger until a real scheduler exists,
    mirroring /admin/outbox/dispatch.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=dispatch_tool_requests status=started actor=%s batch_size=%s", actor, body.batch_size)

    report = port.dispatch_pending_requests(body.batch_size)

    audit_logger.info(
        "action=dispatch_tool_requests status=completed actor=%s scanned=%s dispatched=%s",
        actor, report.scanned, report.dispatched,
    )
    return to_dispatch_tool_requests_response(report)


@router.post("/workflows/{workflow_instance_id}/complete", response_model=WorkflowInstanceResponse)
def complete_workflow(
    workflow_instance_id: UUID,
    request: Request,
    body: CompleteWorkflowRequest,
    port: WorkflowLifecyclePort = Depends(get_workflow_lifecycle_port),
) -> WorkflowInstanceResponse:
    """SPEC-ARO-004 Workflow Instance Aggregate. *Deciding* completion is due is
    SPEC-ARO-010's job (task-graph join-policy evaluation); this endpoint carries out
    the aggregate transition once that decision has been made — manual/ops-triggered
    until that automatic caller exists, mirroring how /recover predates a scheduler.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=complete_workflow status=started workflow_instance_id=%s actor=%s", workflow_instance_id, actor)

    view = port.complete_workflow(to_complete_command(workflow_instance_id, body))

    audit_logger.info(
        "action=complete_workflow status=completed workflow_instance_id=%s actor=%s state=%s workflow_version=%s",
        workflow_instance_id, actor, view.state, view.workflow_version,
    )
    return to_workflow_instance_response(view)


@router.post("/workflows/{workflow_instance_id}/fail", response_model=WorkflowInstanceResponse)
def fail_workflow(
    workflow_instance_id: UUID,
    request: Request,
    body: FailWorkflowRequest,
    port: WorkflowLifecyclePort = Depends(get_workflow_lifecycle_port),
) -> WorkflowInstanceResponse:
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=fail_workflow status=started workflow_instance_id=%s actor=%s", workflow_instance_id, actor)

    view = port.fail_workflow(to_fail_command(workflow_instance_id, body))

    audit_logger.info(
        "action=fail_workflow status=completed workflow_instance_id=%s actor=%s state=%s workflow_version=%s",
        workflow_instance_id, actor, view.state, view.workflow_version,
    )
    return to_workflow_instance_response(view)


@router.post("/workflows/{workflow_instance_id}/cancel", response_model=WorkflowInstanceResponse)
def cancel_workflow(
    workflow_instance_id: UUID,
    request: Request,
    body: CancelWorkflowRequest,
    port: WorkflowLifecyclePort = Depends(get_workflow_lifecycle_port),
) -> WorkflowInstanceResponse:
    """SPEC-ARO-004. *Triggering* a cancel from an upstream ticket-cycle-cancelled event
    is SPEC-ARO-023's job; this endpoint carries out the aggregate transition, reachable
    today through admin/ops override.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=cancel_workflow status=started workflow_instance_id=%s actor=%s", workflow_instance_id, actor)

    view = port.cancel_workflow(to_cancel_command(workflow_instance_id, body))

    audit_logger.info(
        "action=cancel_workflow status=completed workflow_instance_id=%s actor=%s state=%s workflow_version=%s",
        workflow_instance_id, actor, view.state, view.workflow_version,
    )
    return to_workflow_instance_response(view)


@router.get("/poison-events", response_model=PoisonEventListResponse)
def list_poison_events(
    request: Request, limit: int = 50, port: PoisonEventQueryPort = Depends(get_poison_event_query_port)
) -> PoisonEventListResponse:
    """SPEC-ARO-024 10-failure-handling §"Poison Event" step 4: "等待人工修复后 replay" —
    the visibility surface an operator uses to see what needs fixing before replaying it
    (by resending the corrected event under the same eventId to its original endpoint).
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=list_poison_events status=started actor=%s limit=%s", actor, limit)

    views = port.list_poison_events(limit)

    audit_logger.info("action=list_poison_events status=completed actor=%s count=%s", actor, len(views))
    return to_poison_event_list_response(views)


@router.post("/poison-events/{id}/quarantine", response_model=PoisonEventResponse)
def quarantine_poison_event(
    id: UUID, request: Request, port: PoisonEventCommandPort = Depends(get_poison_event_command_port)
) -> PoisonEventResponse:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined" —
    lets an operator flag a poison event as already triaged, distinguishing "seen" from
    "brand new" on the /poison-events visibility surface. A one-way flag, not a status
    machine — the event's only other exit is replay (resending the corrected event
    under the same eventId, unchanged from SPEC-ARO-024).
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=quarantine_poison_event status=started id=%s actor=%s", id, actor)

    view = port.mark_quarantined(id)

    audit_logger.info("action=quarantine_poison_event status=completed id=%s actor=%s", id, actor)
    return to_poison_event_response(view)


@router.get("/audit-events", response_model=AuditEventListResponse)
def list_audit_events(
    request: Request, limit: int = 50, port: AuditRecordQueryPort = Depends(get_audit_record_query_port)
) -> AuditEventListResponse:
    """SPEC-ARO-034 12-observability §"Audit Events": "审计事件必须可长期保存" — the
    visibility surface an operator uses to see what has been recorded, mirroring
    /admin/poison-events' own established shape.
    """
    actor = request.headers.get("X-Actor-Id")
    audit_logger.info("action=list_audit_events status=started actor=%s limit=%s", actor, limit)

    entries = port.list_audit_events(limit)

    audit_logger.info("action=list_audit_events status=completed actor=%s count=%s", actor, len(entries))
    return to_audit_event_list_response(entries)
