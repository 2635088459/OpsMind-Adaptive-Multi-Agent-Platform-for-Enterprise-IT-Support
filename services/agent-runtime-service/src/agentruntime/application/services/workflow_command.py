"""Sole implementation of WorkflowCommandPort: a thin facade that composes the three
named Workflow Instance services (13-package-and-class-design §"Application Layer").
Contains no business rules of its own — the interfaces layer depends only on the
port, never on the individual services directly.
"""

from __future__ import annotations

from agentruntime.application.commands import PauseWorkflowCommand, ResumeWorkflowCommand, StartWorkflowCommand
from agentruntime.application.services.pause_workflow import PauseWorkflowService
from agentruntime.application.services.resume_workflow import ResumeWorkflowService
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.application.views import WorkflowInstanceView


class WorkflowCommandService:
    def __init__(
        self,
        start_workflow_service: StartWorkflowService,
        pause_workflow_service: PauseWorkflowService,
        resume_workflow_service: ResumeWorkflowService,
    ) -> None:
        self._start_workflow_service = start_workflow_service
        self._pause_workflow_service = pause_workflow_service
        self._resume_workflow_service = resume_workflow_service

    def start_workflow(self, command: StartWorkflowCommand) -> WorkflowInstanceView:
        return self._start_workflow_service.start(command)

    def pause_workflow(self, command: PauseWorkflowCommand) -> WorkflowInstanceView:
        return self._pause_workflow_service.pause(command)

    def resume_workflow(self, command: ResumeWorkflowCommand) -> WorkflowInstanceView:
        return self._resume_workflow_service.resume(command)
