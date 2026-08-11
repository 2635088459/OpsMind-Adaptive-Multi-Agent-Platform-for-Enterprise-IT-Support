"""Application-layer exceptions — raised after I/O a pure domain function must not
perform (repository lookups, uniqueness checks, staleness checks).
"""

from __future__ import annotations

from agentruntime.domain.ids import DefinitionVersion, TicketId, WorkflowInstanceId


class DuplicateActiveWorkflowInstanceException(RuntimeError):
    """02-business-invariants §"Workflow Instance Invariants": "At most one active Workflow
    Instance may exist for the same ticketId + ticketCycleId + workflowType." Raised by
    StartWorkflowService after querying WorkflowInstanceRepository, not by the pure domain
    factory.
    """

    def __init__(self) -> None:
        super().__init__("an active workflow instance already exists for this ticketId, ticketCycleId, and workflowType")


class WorkflowInstanceNotFoundException(RuntimeError):
    def __init__(self, workflow_instance_id: WorkflowInstanceId) -> None:
        super().__init__(f"workflow instance not found: {workflow_instance_id}")
        self.workflow_instance_id = workflow_instance_id


class AgentTaskNotFoundException(RuntimeError):
    def __init__(self, reference: str) -> None:
        super().__init__(f"agent task not found: {reference}")


class CheckpointNotFoundException(RuntimeError):
    """SPEC-ARO-006 05-api-contracts "GET /workflows/{workflowInstanceId}/checkpoints/
    latest": raised when the Workflow Instance itself exists but has recorded no
    checkpoint yet — distinct from WorkflowInstanceNotFoundException, which covers the
    instance itself not existing. Should not happen for any instance started through
    StartWorkflowService (SPEC-ARO-005 always writes a STARTED checkpoint), but a query
    must not assume that invariant instead of handling its absence explicitly.
    """

    def __init__(self, workflow_instance_id: WorkflowInstanceId) -> None:
        super().__init__(f"workflow instance {workflow_instance_id} has no recorded checkpoint")
        self.workflow_instance_id = workflow_instance_id


class IdempotencyKeyReusedException(RuntimeError):
    """09-concurrency-and-idempotency §"Command Idempotency": "Same key with different
    request hash must return conflict." Raised by
    agentruntime.application.services.idempotency.CommandIdempotencyGuard for any of the
    five idempotent commands (start, pause, resume, complete task, request tool) when the
    same idempotency_key arrives with different request parameters — the caller must not
    silently retry a materially different request under a key it already used.
    """

    def __init__(self) -> None:
        super().__init__("a different idempotency key already produced this result")


class ClaimTokenMismatchException(RuntimeError):
    """09-concurrency-and-idempotency §"Task Claim": "Worker completion must submit
    claimToken. Mismatch is rejected." Guards against a worker whose lease already expired
    (and was reclaimed by someone else) from still being able to write a result.
    """

    def __init__(self) -> None:
        super().__init__("submitted claim token does not match the agent task's current lease")


class StalePauseGenerationException(RuntimeError):
    """09-concurrency-and-idempotency §"Workflow Version": "For pause/resume, it must also
    validate pauseGeneration." The workflow was paused and/or resumed after this task was
    claimed — the claim is stale and its result must not be accepted.
    """

    def __init__(self) -> None:
        super().__init__("workflow was paused/resumed since this task was claimed; the claim is stale")


class StaleWorkflowVersionException(RuntimeError):
    """SPEC-ARO-009 09-concurrency-and-idempotency §"Workflow Version": "Task worker
    receives workflowVersion when reading a task and must validate it on result
    submission." A general staleness signal alongside StalePauseGenerationException's
    pause/resume-specific one: workflow_version advances on every Workflow Instance
    transition, not only pause/resume (e.g. an admin force-complete/fail/cancel while the
    task was still claimed) — a submitted result against a workflow that has since moved
    on must not be accepted, whatever caused it to move.
    """

    def __init__(self) -> None:
        super().__init__("workflow instance version has changed since this task was claimed; the claim is stale")


class WorkflowNotRunningException(RuntimeError):
    """09-concurrency-and-idempotency §"Task Claim": "Workflow must be in RUNNING."."""

    def __init__(self) -> None:
        super().__init__("workflow instance is not RUNNING; agent tasks cannot be claimed")


class AutomationNotAllowedException(RuntimeError):
    """SPEC-ARO-005 04-use-cases UC-01 step 3: "Query Ticket snapshot and confirm
    automation can start." Raised when a Ticket snapshot is available and its status is
    already terminal (RESOLVED/CLOSED/CANCELLED/FAILED) — a ticket.created event that
    arrives after the ticket has already left automatable territory (e.g. redelivered
    late, or the ticket was closed before Runtime got to it) must not start a Workflow
    Instance. When no snapshot is available at all (NoOpTicketSnapshotPort, pending the
    real Ticket Workflow query adapter), this check is skipped rather than blocking every
    start — see NoOpTicketSnapshotPort's own docstring.
    """

    def __init__(self, ticket_id: TicketId, ticket_status: str) -> None:
        super().__init__(f"ticket {ticket_id} is in status {ticket_status}; automation cannot start")
        self.ticket_id = ticket_id
        self.ticket_status = ticket_status


class StaleRuntimeEventException(RuntimeError):
    """SPEC-ARO-001 event-contract: "Duplicate/stale/invalid events must not advance Workflow
    again." Raised by ConsumeRuntimeEventService when an event's expected_workflow_version no
    longer matches the persisted workflow version.
    """

    def __init__(self, event_id: str) -> None:
        super().__init__(f"stale runtime event, workflow has already advanced past it: {event_id}")


class DefinitionVersionMismatchException(RuntimeError):
    """02-business-invariants §"Workflow Instance Invariants": "recovery must not silently
    switch definitions."
    """

    def __init__(self, persisted: DefinitionVersion, expected: DefinitionVersion) -> None:
        super().__init__(f"workflow instance is bound to definitionVersion {persisted} but recovery expected {expected}")


class WorkflowInstanceVersionConflictException(RuntimeError):
    """SPEC-ARO-001 domain-rules: "every write operation must have idempotency or version protection."."""

    def __init__(self) -> None:
        super().__init__("workflow instance was modified concurrently; retry with the latest version")


class AgentTaskVersionConflictException(RuntimeError):
    def __init__(self) -> None:
        super().__init__("agent task was modified concurrently; retry with the latest version")
