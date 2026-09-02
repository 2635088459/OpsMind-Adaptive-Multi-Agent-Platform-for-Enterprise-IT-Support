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


class ToolRequestNotFoundException(RuntimeError):
    """SPEC-ARO-020 06-event-contracts §"tool.completed.v1": "必须匹配 Runtime 已持久化的
    Tool Request" — a tool.completed.v1 event whose toolRequestId does not match any
    persisted Tool Request cannot be applied.
    """

    def __init__(self, reference: str) -> None:
        super().__init__(f"tool request not found: {reference}")


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


class PauseCheckpointNotFoundException(RuntimeError):
    """SPEC-ARO-014/015 04-use-cases UC-07 Resume step 4: "Read the PAUSED checkpoint."
    02-business-invariants §"Checkpoint Invariants": "every recoverable waiting state must
    include a checkpoint" — PauseWorkflowService (SPEC-ARO-012) always writes one before
    a Workflow Instance reaches PAUSED, so a resume that finds none indicates that
    invariant was violated somewhere else (data loss/corruption, or a future admin
    tool bypassing PauseWorkflowService), not a normal, retriable client error. Resume
    must fail loudly rather than silently proceed without the recoverable snapshot the
    rest of this domain's crash-recovery story depends on.
    """

    def __init__(self, workflow_instance_id: WorkflowInstanceId) -> None:
        super().__init__(f"workflow instance {workflow_instance_id} is PAUSED but has no PAUSE_POINT checkpoint")
        self.workflow_instance_id = workflow_instance_id


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


class PoisonRuntimeEventException(RuntimeError):
    """SPEC-ARO-024 10-failure-handling §"Poison Event": raised once a runtime event's
    opaque payload could not even be parsed/understood by its type-specific consumer
    (malformed JSON, a missing required field, an unparsable id) — the "invalid" leg of
    "Duplicate/stale/invalid events must not advance Workflow again," distinct from a
    well-classified business rejection like StaleRuntimeEventException/
    WorkflowInstanceNotFoundException, both of which mean the event *was* understood.
    """

    def __init__(self, event_id: str, reason: str) -> None:
        self.event_id = event_id
        self.reason = reason
        super().__init__(f"poison runtime event {event_id}: {reason}")


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


class CapabilityNotAuthorizedException(RuntimeError):
    """SPEC-ARO-032 11-security §"Authorization"/§"Tool Gateway 强制路径": raised when a
    Tool Request's declared capability is not one CapabilityPolicyPort authorizes for
    the requesting Agent Task's own agent_role. Only reachable when both agent_role and
    capability are present — see RequestToolService's own docstring for why an absent
    agent_role is a pass-through, not an implicit denial.
    """

    def __init__(self, agent_role: str, capability: str) -> None:
        super().__init__(f"agent_role {agent_role!r} is not authorized for capability {capability!r}")
        self.agent_role = agent_role
        self.capability = capability


class PoisonEventNotFoundException(RuntimeError):
    """SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined" —
    raised when the id an operator names does not match any recorded poison event.
    """

    def __init__(self, id: object) -> None:
        super().__init__(f"poison event not found: {id}")


class OutboundAuthenticationException(RuntimeError):
    """SPEC-ARO-043 domain-rules: "if a token cannot be obtained, the outbound call fails
    closed." Raised by OutboundServiceTokenProviderPort implementations (and by any
    outbound client that catches a token-acquisition failure) — never allow a caller
    to proceed unauthenticated or with a stale/expired token.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(f"could not obtain an outbound service token: {reason}")
        self.reason = reason


class TicketCreationFailedException(RuntimeError):
    """SPEC-ARO-038 domain-rules: the outbound call to 02-ticket-workflow's real
    POST /api/v1/tickets did not succeed. StartConversationService never proceeds to
    create a WorkflowInstance when this is raised — "WorkflowInstance creation never
    happens without a prior, successful, real ticket creation."
    """

    def __init__(self, reason: str) -> None:
        super().__init__(f"ticket creation failed: {reason}")
        self.reason = reason


class TicketTriageFailedException(RuntimeError):
    """SPEC-ARO-041: the outbound call to 02-ticket-workflow's real
    POST /{ticketId}/triage did not succeed. SendMessageService never claims escalation
    succeeded when this is raised — mirrors TicketCreationFailedException's own
    "we function correctly, a downstream dependency did not" posture.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(f"ticket triage failed: {reason}")
        self.reason = reason


class GovernanceApprovalRequestFailedException(RuntimeError):
    """SPEC-ARO-040: the outbound call to 06-policy-approval-governance's real
    request-approval endpoint did not succeed. SendMessageService's confirm path never
    claims "awaiting-approval" when this is raised — mirrors TicketTriageFailedException's
    own "we function, a dependency did not" posture.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(f"governance approval request failed: {reason}")
        self.reason = reason


class ActionNotFoundException(RuntimeError):
    """SPEC-ARO-040: raised when an actionId (an AgentTaskId) does not resolve to any
    AgentTask belonging to the named conversation, or does not belong to that
    conversation at all.
    """

    def __init__(self, action_id: object) -> None:
        super().__init__(f"action not found: {action_id}")


class ActionNotAwaitingConfirmationException(RuntimeError):
    """SPEC-ARO-040 domain-rules: "the same actionId can never be confirmed or
    declined a second time with a new real side effect — a repeat returns the
    current, real terminal state." Raised only when that current state cannot be
    honestly rendered as one of the declared outcome shapes (e.g. the task never
    entered AWAITING_USER_CONFIRMATION in the first place) — a genuine repeat of an
    already-decided action is handled by returning its real current outcome instead
    of raising this.
    """

    def __init__(self, action_id: object, current_state: object) -> None:
        super().__init__(f"action {action_id} is in state {current_state}, not awaiting confirmation")


class EscalationRoutingNotConfiguredException(RuntimeError):
    """SPEC-ARO-041 domain-rules: real triage requires a real categoryId/supportQueueId
    that exists in 02-ticket-workflow's own reference-data catalog — no seed data for
    either exists anywhere in this platform yet (confirmed by reading ticket-workflow's
    own migrations directly), so this service cannot safely invent one. Raised when
    Settings.escalation_default_category_id/escalation_default_support_queue_id are
    unconfigured — fails closed (a clear, visible error) rather than fabricating a
    category/queue id that may not exist, or silently skipping escalation while still
    claiming success.
    """

    def __init__(self) -> None:
        super().__init__(
            "escalation routing is not configured (escalation_default_category_id / "
            "escalation_default_support_queue_id) — an operator must configure real "
            "category/support-queue ids from 02-ticket-workflow's own reference data"
        )


class ConversationNotFoundException(RuntimeError):
    """SPEC-ARO-042: raised when a conversationId (== workflowInstanceId) does not
    resolve to any Workflow Instance, or resolves to one whose workflow_type is not
    conversational_intake (SPEC-ARO-037) — a workflow instance belonging to some other
    workflow_type is never reshaped into a conversation view.
    """

    def __init__(self, conversation_id: object) -> None:
        super().__init__(f"conversation not found: {conversation_id}")


class ConversationAccessDeniedException(RuntimeError):
    """SPEC-ARO-042 domain-rules: "a conversation belonging to a different employee is
    never returned" — raised when the resolved Workflow Instance's own requester
    subject does not match the calling employee's asserted identity.
    """

    def __init__(self) -> None:
        super().__init__("this conversation does not belong to the calling employee")
