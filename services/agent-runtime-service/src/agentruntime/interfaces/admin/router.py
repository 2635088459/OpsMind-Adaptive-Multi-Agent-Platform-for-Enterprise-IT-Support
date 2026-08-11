"""13-package-and-class-design §"Interfaces": "Admin controller." SPEC-ARO-001
api-contract: "Admin APIs must record audit" — every recovery/dispatch request is
logged with the requesting actor before and after the operation. A dedicated
audit-record port/table (mirroring the sibling Ticket Workflow service's
AuditRecordPort) is deferred; this is SPEC-ARO-001's structured-logging baseline.
"""

from __future__ import annotations

import logging
from uuid import UUID

from fastapi import APIRouter, Depends, Request

from agentruntime.application.ports_in import OutboxDispatchPort, RecoveryPort, WorkflowLifecyclePort
from agentruntime.container import get_outbox_dispatch_port, get_recovery_port, get_workflow_lifecycle_port
from agentruntime.interfaces.admin.mapper import (
    to_cancel_command,
    to_command,
    to_complete_command,
    to_dispatch_response,
    to_fail_command,
    to_response,
    to_workflow_instance_response,
)
from agentruntime.interfaces.admin.schemas import (
    CancelWorkflowRequest,
    CompleteWorkflowRequest,
    DispatchOutboxEventsRequest,
    DispatchReportResponse,
    FailWorkflowRequest,
    RecoverWorkflowRequest,
    RecoveryReportResponse,
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
