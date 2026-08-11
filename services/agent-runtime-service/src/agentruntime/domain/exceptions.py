"""Domain exceptions — thrown by domain aggregates when a rule is violated.

Distinct from application-layer exceptions (agentruntime.application.exceptions),
which are raised after I/O a pure domain function must not perform (repository
lookups, uniqueness checks).
"""

from __future__ import annotations

from agentruntime.domain.enums import AgentTaskState, WorkflowState


class InvalidWorkflowTransitionException(RuntimeError):
    """Thrown when a Workflow Instance is not in the single state required for a transition."""

    def __init__(self, current_state: WorkflowState, required_state: WorkflowState) -> None:
        super().__init__(f"workflow is in state {current_state} but requires {required_state}")
        self.current_state = current_state
        self.required_state = required_state


class InvalidWorkflowStateException(RuntimeError):
    """Thrown when a Workflow Instance's current state is outside the set of allowed states."""

    def __init__(self, current_state: WorkflowState, allowed_states: frozenset[WorkflowState]) -> None:
        super().__init__(f"workflow is in state {current_state} but requires one of {allowed_states}")
        self.current_state = current_state
        self.allowed_states = allowed_states


class InvalidAgentTaskTransitionException(RuntimeError):
    """Thrown when an Agent Task is not in a state that allows the requested transition."""

    def __init__(self, current_state: AgentTaskState, required_description: str) -> None:
        super().__init__(f"agent task is in state {current_state} but requires {required_description}")
        self.current_state = current_state


class AgentTaskDependencyNotSatisfiedException(RuntimeError):
    """02-business-invariants §"Agent Task Invariants": "All dependsOn tasks must complete before execution."."""

    def __init__(self) -> None:
        super().__init__("agent task has one or more dependsOn tasks that have not completed")


class AgentTaskAlreadyClaimedException(RuntimeError):
    """13-package-and-class-design: "Worker claim must use a lease; after lease expiry the task can be claimed again."."""

    def __init__(self) -> None:
        super().__init__("agent task is already claimed under an unexpired lease")
