"""Output ports (13-package-and-class-design §"Ports"). Structural typing.Protocol,
the idiomatic Python stand-in for Java interfaces — infrastructure adapters satisfy
these by shape, without inheriting from anything here.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Protocol

from agentruntime.application.commands import WorkflowDefinitionInput
from agentruntime.application.records import (
    AgentTaskRecord,
    ApprovalRequestRef,
    AuditRecordEntry,
    CheckpointRecord,
    CommandIdempotencyRecord,
    CreatedTicketRef,
    KnowledgeSnippet,
    OutboxRecord,
    PoisonEventRecord,
    ReasoningOutcome,
    TicketSnapshot,
    ToolDispatchAcknowledgement,
    ToolRequestRecord,
    TriagedTicketRef,
    WorkflowInstanceRecord,
)
from agentruntime.domain.enums import CheckpointType
from agentruntime.domain.ids import (
    AgentTaskId,
    IdempotencyKey,
    TicketCycleId,
    TicketId,
    ToolRequestId,
    WorkflowInstanceId,
    WorkflowType,
)


class ClockPort(Protocol):
    def now(self) -> datetime: ...


class WorkflowInstanceRepository(Protocol):
    def find_by_id(self, workflow_instance_id: WorkflowInstanceId) -> WorkflowInstanceRecord | None: ...

    def find_active_by_ticket_cycle_and_type(
        self, ticket_id: TicketId, ticket_cycle_id: TicketCycleId, workflow_type: WorkflowType
    ) -> WorkflowInstanceRecord | None:
        """02-business-invariants §"Workflow Instance Invariants": at most one active instance per key."""
        ...

    def find_by_ticket_id(self, ticket_id: TicketId) -> list[WorkflowInstanceRecord]:
        """SPEC-ARO-006 05-api-contracts "GET /workflows/by-ticket/{ticketId}": every Workflow
        Instance ever started for a ticket, across all ticket cycles and terminal/
        non-terminal states — unlike find_active_by_ticket_cycle_and_type, not filtered
        to "currently active".
        """
        ...

    def save(self, record: WorkflowInstanceRecord) -> WorkflowInstanceRecord:
        """Inserts a brand new record, or replaces an existing one under optimistic-concurrency
        control: the stored record's workflow_version must equal record.workflow_version - 1.

        Raises WorkflowInstanceVersionConflictException if the stored version has moved on.
        """
        ...

    def find_non_terminal(self, limit: int) -> list[WorkflowInstanceRecord]:
        """SPEC-ARO-028 10-failure-handling §"Runtime 崩溃后怎么恢复": "Recovery worker 周期性
        扫描非终态 Workflow Instance" — the query the scanner is built around. Oldest
        updated_at first, so a batch run makes steady progress across repeated calls
        rather than repeatedly re-scanning the same head of the result set.
        """
        ...

    def find_most_recent_by_requester_and_workflow_type(
        self, requester_subject: str, workflow_type: WorkflowType
    ) -> WorkflowInstanceRecord | None:
        """SPEC-ARO-042 api-contract: "find the most recent active/escalated conversation
        belonging to a given requester identity" — supports domain 09's UC-EP-06 (a
        returning employee who does not already know their conversationId). "Active or
        escalated" deliberately spans both a still-RUNNING conversation and one that
        already reached its terminal state via SPEC-ARO-041's own escalation (which the
        employee should still be able to resume into read-only, to see the resulting
        ticket's status) — so this returns the single most recent instance by
        created_at regardless of state, never filtered to non-terminal only. None if the
        requester has started no conversation at all.
        """
        ...


class AgentTaskRepository(Protocol):
    def find_by_id(self, agent_task_id: AgentTaskId) -> AgentTaskRecord | None: ...

    def find_by_workflow_instance_id_and_task_key(
        self, workflow_instance_id: WorkflowInstanceId, task_key: str
    ) -> AgentTaskRecord | None: ...

    def find_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> list[AgentTaskRecord]: ...

    def find_claimable_ready_tasks(self, agent_role: str, limit: int) -> list[AgentTaskRecord]:
        """SPEC-ARO-009 05-api-contracts "Claim Task" / 09-concurrency-and-idempotency:
        "Use FOR UPDATE SKIP LOCKED or an equivalent mechanism to prevent multiple workers
        from claiming the same task." Up to `limit` AgentTaskState.READY rows whose
        agent_role matches, oldest first — steers concurrent pollers away from rows
        another in-flight transaction already has under consideration (Postgres:
        FOR UPDATE SKIP LOCKED), but does not by itself guarantee exclusivity: the actual,
        unconditional safety net is the same optimistic task_version check every save()
        already enforces (ClaimAgentTaskService.claim_ready() treats a version conflict on
        a candidate from this list as "lost the race", not an error). Does not filter by
        the owning Workflow Instance's state — the caller checks that per candidate, since
        an in-memory adapter has no cross-repository join to express it either.
        """
        ...

    def save(self, record: AgentTaskRecord) -> AgentTaskRecord:
        """Inserts a brand new record, or replaces an existing one under optimistic-concurrency
        control: the stored record's task_version must equal record.task_version - 1.

        Raises AgentTaskVersionConflictException if the stored version has moved on.
        """
        ...

    def find_expired_leases(self, now: datetime, limit: int) -> list[AgentTaskRecord]:
        """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5: up to `limit`
        AgentTaskState.CLAIMED/RUNNING rows whose lease_expires_at has already passed
        `now`, oldest lease first — what RecoverExpiredLeaseTasksService.scan_and_recover()
        is built on, mirroring WorkflowInstanceRepository.find_non_terminal()'s own
        SPEC-ARO-028 shape.
        """
        ...


class CheckpointRepository(Protocol):
    """02-business-invariants §"Checkpoint Invariants": "A checkpoint must exist before any
    external side effect."
    """

    def save(self, record: CheckpointRecord) -> CheckpointRecord: ...

    def find_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> list[CheckpointRecord]: ...

    def find_latest_by_workflow_instance_id(self, workflow_instance_id: WorkflowInstanceId) -> CheckpointRecord | None:
        """SPEC-ARO-006 05-api-contracts "GET /workflows/{workflowInstanceId}/checkpoints/
        latest" — most-recently-recorded checkpoint, or None if the instance has none yet.
        """
        ...

    def find_latest_by_workflow_instance_id_and_type(
        self, workflow_instance_id: WorkflowInstanceId, checkpoint_type: CheckpointType
    ) -> CheckpointRecord | None:
        """SPEC-ARO-008 04-use-cases UC-02 step 6: CoordinateAgentTasksService.
        unlock_downstream_tasks() uses this with CheckpointType.STARTED to re-derive the
        task graph — the only durable record of its shape (no Workflow Definition catalog
        table exists) — after a later task completes. STARTED is written exactly once per
        instance, so "latest" and "only" coincide here, but the method stays generically
        named/typed for any other checkpoint type a future caller needs the newest of.
        """
        ...


class ToolRequestRepository(Protocol):
    def save(self, record: ToolRequestRecord) -> ToolRequestRecord: ...

    def find_by_id(self, tool_request_id: ToolRequestId) -> ToolRequestRecord | None: ...

    def find_pending(self, limit: int) -> list[ToolRequestRecord]:
        """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 6: the
        durable, decoupled unit of work DispatchToolRequestsService scans — a PENDING Tool
        Request persisted by a committed RequestToolService transaction, not yet handed to
        ToolGatewayPort.
        """
        ...


class ProcessedEventRepository(Protocol):
    """02-business-invariants §"Event Handling Invariants": "Every consumed event must be
    checked against or written to processed_events in the same transaction."
    09-concurrency-and-idempotency §"消费事件幂等": "每个 consumer 在处理事件前检查 eventId,
    consumerName, eventType" — consumer_name is a required identity component of the dedup
    key, not an optional detail (07-data-model's own unique key is `event_id,
    consumer_name`): two distinct logical consumers (SPEC-ARO-005's
    ConsumeTicketCreatedService, SPEC-ARO-001's ConsumeRuntimeEventService) processing an
    event with the same event_id must not be treated as the same processed record.
    """

    def is_processed(self, event_id: str, consumer_name: str) -> bool: ...

    def mark_processed(
        self,
        event_id: str,
        consumer_name: str,
        processed_at: datetime,
        event_type: str | None = None,
        workflow_instance_id: WorkflowInstanceId | None = None,
    ) -> None: ...


class PoisonEventRepository(Protocol):
    """SPEC-ARO-024 10-failure-handling §"Poison Event": "写入 poison event 表或 dead
    letter" — deliberately a separate table from processed_events/outbox_events, since a
    poisoned delivery is neither "already applied" nor "waiting to be published"; it is
    parked for manual investigation and possible replay.
    """

    def record(self, record: PoisonEventRecord) -> PoisonEventRecord: ...

    def find_all(self, limit: int) -> list[PoisonEventRecord]:
        """Newest first — the admin visibility surface a human uses to see what needs
        fixing before replaying (10-failure-handling step 4).
        """
        ...

    def find_by_id(self, id: uuid.UUID) -> PoisonEventRecord | None: ...

    def mark_quarantined(self, id: uuid.UUID, quarantined_at: datetime) -> None:
        """SPEC-ARO-031 05-api-contracts §"Admin API": "mark poison event quarantined"."""
        ...


class AuditRecordRepository(Protocol):
    """SPEC-ARO-034 12-observability §"Audit Events": "审计事件必须可长期保存." Append-only —
    no mark_*/update method, unlike PoisonEventRepository or OutboxRepository: an audit
    row's own fields never change after being written.
    """

    def append(self, entry: AuditRecordEntry) -> None: ...

    def find_all(self, limit: int) -> list[AuditRecordEntry]:
        """Newest first — the admin visibility surface, mirroring
        PoisonEventRepository.find_all()'s own shape.
        """
        ...


class OutboxRepository(Protocol):
    """02-business-invariants §"Event Handling Invariants": "Every published event must go
    through outbox. Broker messages must not be sent synchronously inside business
    transactions." 08-transaction-and-outbox §"Outbox Publisher": DispatchOutboxEventsService
    is the only caller of find_dispatchable/mark_*; every other application service only
    ever calls append(). Real broker wiring (RabbitMQ) is phase-07
    (runtime-event-publishing) — DispatchOutboxEventsService publishes through
    EventPublisherPort, not directly.
    """

    def append(self, record: OutboxRecord) -> None: ...

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]:
        """08-transaction-and-outbox §"Outbox Publisher": "Scan by available_at."."""
        ...

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None: ...

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None: ...

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None: ...

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]:
        """SPEC-ARO-030 08-transaction-and-outbox §"Outbox Publisher": what
        OutboxStatus.DEAD_LETTER's own docstring ("requires manual/ops intervention")
        is built on — the visibility half of that intervention.
        """
        ...

    def requeue(self, outbox_id: uuid.UUID, available_at: datetime) -> None:
        """SPEC-ARO-030: moves a DEAD_LETTER row back to PENDING with attempts reset to
        0 and available_at reset to `available_at` — the manual/ops intervention
        OutboxStatus.DEAD_LETTER's own docstring names, so DispatchOutboxEventsService's
        existing publish/retry/backoff cycle can pick the row back up exactly as it would
        a fresh one.
        """
        ...


class ToolGatewayPort(Protocol):
    """13-package-and-class-design §"Class Boundaries": "ToolGatewayPort sends only
    persisted Tool Requests." 02-business-invariants §"Tool Gateway Boundary": this is
    Runtime's only exit to tool systems — Agent SDKs, Agent Workers, and Task Handlers must
    never hold a Tool client directly, and no application service other than
    RequestToolService may depend on this port (enforced by
    tests/architecture/test_tool_gateway_boundary.py).
    """

    def dispatch(self, request: ToolRequestRecord) -> ToolDispatchAcknowledgement: ...


class CapabilityPolicyPort(Protocol):
    """SPEC-ARO-032 11-security §"Tool Gateway 强制路径"/§"Authorization": the capability
    taxonomy SPEC-ARO-017's own ToolRequested.capability field was carried-but-never-
    validated against ("no capability taxonomy or authorization check exists for it
    yet — that's SPEC-ARO-032's job"). Consulted by RequestToolService before a Tool
    Request is ever persisted — an authorization gate, not a query about Runtime state.
    """

    def is_authorized(self, agent_role: str, capability: str) -> bool: ...


class TicketSnapshotPort(Protocol):
    """02-business-invariants §"State Ownership": read-only view of Ticket state; never a
    write path.
    """

    def find_snapshot(self, ticket_id: TicketId) -> TicketSnapshot | None: ...


class WorkflowDefinitionCatalogPort(Protocol):
    """SPEC-ARO-005 04-use-cases UC-01 step 6: "Planner creates the Agent Task graph" —
    the Planner (domain.planner.plan()) only resolves a graph out of an already-bound
    WorkflowDefinition; something has to supply that definition when Runtime itself
    decides to start a Workflow Instance from a consumed ticket.created event, since
    there the caller carries only ticketId/ticketCycleId/priority/category, never a full
    definition (unlike the direct REST /workflows command, which still requires the
    caller to supply one — see StartWorkflowCommand's docstring). A full authorable
    Workflow Definition catalog (table, versioning, category-routing rules) has no
    07-data-model table yet and stays out of scope here — same "capabilities reserved
    for later phases" deferral domain.planner.plan() already documents for phase-02
    agent-task-orchestration.
    """

    def resolve_for_ticket(self, category: str) -> WorkflowDefinitionInput: ...


class EventPublisherPort(Protocol):
    """08-transaction-and-outbox §"Outbox Publisher". Runtime's only exit for publishing —
    mirrors ToolGatewayPort's boundary role, but for outbound events instead of tool
    calls: only DispatchOutboxEventsService may depend on this port. Real RabbitMQ wiring
    is phase-07 (runtime-event-publishing); SPEC-ARO-003 ships a logging adapter.
    """

    def publish(self, record: OutboxRecord) -> bool:
        """Returns True on success. Must never raise for an ordinary delivery failure —
        DispatchOutboxEventsService interprets False as "retry with backoff", not an
        application bug.
        """
        ...


class CommandIdempotencyRepository(Protocol):
    """07-data-model §"command_idempotency" / 09-concurrency-and-idempotency
    §"Command Idempotency". See CommandIdempotencyRecord's docstring for who uses it.
    """

    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None: ...

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord: ...


class OutboundServiceTokenProviderPort(Protocol):
    """SPEC-ARO-043 (phase-10 Conversational Intake): this service's only source of a
    JWT for its own outbound calls to 02-ticket-workflow/06-policy-approval-governance
    (SPEC-ARO-038/040/041). domain-rules: "no caller of the outbound client is allowed
    to bypass it with its own ad-hoc token handling" — every outbound HTTP adapter
    depends on this port, never on a Keycloak client library directly.
    """

    def get_token(self) -> str:
        """Returns a currently-valid bearer token, acquiring or refreshing one as
        needed. Raises OutboundAuthenticationException if a token cannot be obtained —
        domain-rules: "fails closed ... never silently proceeds unauthenticated or with
        a stale/expired token."
        """
        ...


class TicketWorkflowClientPort(Protocol):
    """SPEC-ARO-038: this service's only exit to 02-ticket-workflow's own real,
    authentication-enforced HTTP endpoints — mirrors ToolGatewayPort's "one dedicated
    port per external system" boundary role. Real HTTP wiring is
    infrastructure.ticket_workflow_client's HttpTicketWorkflowClient.

    create_ticket() deliberately does NOT authenticate via
    OutboundServiceTokenProviderPort (SPEC-ARO-043) — reading ticket-workflow's own
    real PublicTicketController confirmed it records `jwt.getSubject()` directly as the
    ticket's requesterId, with no "on behalf of" mechanism. A service-identity token
    minted for agent-runtime-service itself would therefore record the wrong
    requester (this service, not the employee) — exactly the domain-rules violation
    SPEC-ARO-038 itself warns against ("never a service-account identity standing in
    for the employee"). The correct token is instead the employee's own bearer token,
    already issued by 01-user-access-authentication's real OIDC login and forwarded
    unmodified from the inbound POST /api/v1/conversations request — see
    interfaces.conversation.security's own docstring. SPEC-ARO-043's service identity
    remains the real mechanism for calls that are inherently service-to-service
    (SPEC-ARO-041's triage-as-automation-agent call).
    """

    def create_ticket(self, forwarded_bearer_token: str, idempotency_key: str) -> CreatedTicketRef:
        """Calls the real POST /api/v1/tickets, forwarding forwarded_bearer_token
        as-is in the Authorization header. domain-rules: "the ticket created is always
        real ... never fabricated or simulated locally." Raises
        TicketCreationFailedException on any non-success response.
        """
        ...

    def triage_ticket(
        self, ticket_id: TicketId, current_version: int, category_id: str, support_queue_id: str, priority: str,
        reason: str, idempotency_key: str,
    ) -> TriagedTicketRef:
        """SPEC-ARO-041: calls the real POST /{ticketId}/triage as this service's own
        automated-agent identity (SPEC-ARO-043's service token — unlike create_ticket(),
        this call is genuinely service-to-service, so the employee's own forwarded
        token is never used here). category_id/support_queue_id are real UUIDs from
        02-ticket-workflow's own reference-data catalog (confirmed by reading
        TriageTicketRequest/TriageTicketApplicationService directly — no free-text
        "category hint" is accepted); current_version is sent as the If-Match header.
        Raises TicketTriageFailedException on any non-success response, including a
        version conflict (the caller does not retry with a refreshed version itself —
        see SendMessageService's own docstring for why).
        """
        ...


class KnowledgeRetrievalPort(Protocol):
    """SPEC-ARO-039: this service's only exit to 04-memory-knowledge's real
    POST /internal/memory/v1/search. Unlike ToolGatewayPort/TicketWorkflowClientPort, a
    retrieval failure is not fatal to the message turn it supports — domain-rules: "a
    retrieval failure degrades to a plainer answer or an escalation, never a
    hallucinated citation" — so implementations return an empty list on failure rather
    than raising.
    """

    def search(self, query: str, workflow_instance_id: WorkflowInstanceId, requester_subject: str) -> list[KnowledgeSnippet]: ...


class GovernanceApprovalClientPort(Protocol):
    """SPEC-ARO-040: this service's only exit to 06-policy-approval-governance's real
    POST /api/v1/approval-requests. Genuinely service-to-service (mirrors
    TicketWorkflowClientPort.triage_ticket()'s own reasoning, not create_ticket()'s) —
    authenticates via OutboundServiceTokenProviderPort (SPEC-ARO-043), acquired
    internally by the real adapter, never passed in by the caller.
    """

    def request_approval(
        self, agent_task_id: AgentTaskId, workflow_instance_id: WorkflowInstanceId, ticket_id: TicketId, risk_level: str,
        reason: str,
    ) -> ApprovalRequestRef:
        """Raises GovernanceApprovalRequestFailedException on any non-success response."""
        ...


class ConversationReasoningPort(Protocol):
    """SPEC-ARO-039: decides which of the 3 response shapes a message turn produces.
    No real LLM/LangGraph integration exists anywhere in this codebase yet (confirmed
    by grepping the whole service and the frozen technology-baseline's own "Agent
    Orchestration: LangGraph ... Provisional" status) — the shipped adapter
    (infrastructure.conversation_reasoning.StaticConversationReasoningAdapter) is a
    deliberately simple, honestly-labeled placeholder for that future integration,
    mirroring LoggingToolGatewayPort/NoOpTicketSnapshotPort/StaticCapabilityPolicyAdapter's
    own "safe, real, swappable placeholder until the genuine adapter lands" precedent.
    """

    def decide(self, message_text: str, knowledge_snippets: list[KnowledgeSnippet]) -> ReasoningOutcome: ...
