"""Sole implementation of WorkflowLifecyclePort: a thin facade that composes the three
terminal Workflow Instance services (13-package-and-class-design §"Application Layer").
Contains no business rules of its own, mirroring WorkflowCommandService. Kept separate
from WorkflowCommandPort/WorkflowCommandService (start/pause/resume) because these three
operations are reached through the admin surface today (SPEC-ARO-004) rather than the
general internal-service command surface — see agentruntime.interfaces.admin.router.
"""

from __future__ import annotations

from agentruntime.application.commands import CancelWorkflowCommand, CompleteWorkflowCommand, FailWorkflowCommand
from agentruntime.application.services.cancel_workflow import CancelWorkflowService
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.application.views import WorkflowInstanceView


class WorkflowLifecycleService:
    def __init__(
        self,
        complete_workflow_service: CompleteWorkflowService,
        fail_workflow_service: FailWorkflowService,
        cancel_workflow_service: CancelWorkflowService,
    ) -> None:
        self._complete_workflow_service = complete_workflow_service
        self._fail_workflow_service = fail_workflow_service
        self._cancel_workflow_service = cancel_workflow_service

    def complete_workflow(self, command: CompleteWorkflowCommand) -> WorkflowInstanceView:
        return self._complete_workflow_service.complete(command)

    def fail_workflow(self, command: FailWorkflowCommand) -> WorkflowInstanceView:
        return self._fail_workflow_service.fail(command)

    def cancel_workflow(self, command: CancelWorkflowCommand) -> WorkflowInstanceView:
        return self._cancel_workflow_service.cancel(command)
