package dev.opsmind.ticketworkflow.ticket.domain.model;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalExpiredApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalGrantedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalRejectedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalWaitStarted;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAutoApprovalApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAutoClosed;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketAssignmentUpdated;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCancelled;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketClosed;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalated;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketEscalationResumed;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketDomainEvent;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReassigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketReopened;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolved;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolvedWithVerification;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketResolutionConfirmed;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketStatusChanged;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionCompletedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionFailedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolResultUnknownRecorded;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketTriaged;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationStarted;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationFailureApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationSuccessApplied;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUnassigned;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputRequested;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputResumed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AssigneeRequiredForCurrentStatusException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AssignmentRequiresAChangeException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AutoCloseNotYetDueException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.ReassignmentRequiresDifferentAssigneeException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketAlreadyAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.CancelReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.CloseReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.OwnershipStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.ReopenReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.ResolutionConfirmationReasonCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDescription;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketTitle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Ticket {

    private final TicketId id;
    private final TicketDisplayId displayId;
    private final RequesterId requesterId;
    private final TicketTitle title;
    private final TicketDescription description;
    private final ApplicationCode applicationCode;
    private final TicketSource source;
    private final String category;
    private final String subcategory;
    private final TicketPriority priority;
    private final TicketStatus status;
    private final String currentTeamId;
    private final String currentSupportUserId;
    private final String activeWorkflowId;
    private final UUID currentResolutionCycleId;
    private final Instant autoCloseDueAt;
    private final Instant resolvedAt;
    private final Instant closedAt;
    private final Instant cancelledAt;
    private final String cancelReasonCode;
    private final String closeReasonCode;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;
    private final String createdByType;
    private final String createdById;
    private final List<TicketDomainEvent> domainEvents = new ArrayList<>();

    private Ticket(
        TicketId id,
        TicketDisplayId displayId,
        RequesterId requesterId,
        TicketTitle title,
        TicketDescription description,
        ApplicationCode applicationCode,
        TicketSource source,
        UUID currentResolutionCycleId,
        Instant createdAt,
        Instant updatedAt,
        long version
    ) {
        this.id = id;
        this.displayId = displayId;
        this.requesterId = requesterId;
        this.title = title;
        this.description = description;
        this.applicationCode = applicationCode;
        this.source = source;
        this.category = null;
        this.subcategory = null;
        this.priority = TicketPriority.UNASSIGNED;
        this.status = TicketStatus.NEW;
        this.currentTeamId = null;
        this.currentSupportUserId = null;
        this.activeWorkflowId = null;
        this.currentResolutionCycleId = currentResolutionCycleId;
        this.autoCloseDueAt = null;
        this.resolvedAt = null;
        this.closedAt = null;
        this.cancelledAt = null;
        this.cancelReasonCode = null;
        this.closeReasonCode = null;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.createdByType = "EMPLOYEE";
        this.createdById = requesterId.value();
    }

    public static Ticket create(
        TicketId id,
        TicketDisplayId displayId,
        RequesterId requesterId,
        TicketTitle title,
        TicketDescription description,
        ApplicationCode applicationCode,
        TicketSource source,
        UUID initialResolutionCycleId,
        Instant now
    ) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayId, "displayId must not be null");
        Objects.requireNonNull(requesterId, "requesterId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(applicationCode, "applicationCode must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(initialResolutionCycleId, "initialResolutionCycleId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        Ticket ticket = new Ticket(
            id,
            displayId,
            requesterId,
            title,
            description,
            applicationCode,
            source,
            initialResolutionCycleId,
            now,
            now,
            0L
        );

        ticket.domainEvents.add(new TicketCreated(
            id,
            displayId,
            requesterId,
            applicationCode,
            source,
            0L,
            now
        ));

        return ticket;
    }

    /**
     * SPEC-TW-007 §2: {@code OPEN → TRIAGED} ("OPEN" is this codebase's
     * {@link TicketStatus#NEW}, see {@link TicketStatus}'s Javadoc).
     * Write commands in this codebase never rehydrate a full {@link
     * Ticket} instance from storage (the guard-projection pattern
     * established by SPEC-TW-004 loads only the fields a command needs) —
     * this is therefore a stateless rule over the caller-supplied current
     * status/version rather than an instance method mutating a loaded
     * aggregate, but it stays on {@code Ticket} because the rule belongs
     * to the Ticket aggregate's contract, not to any one command's
     * application service. Catalog existence/active checks and queue
     * authorization are the caller's responsibility (they need I/O this
     * pure method must not perform); this method only enforces the state
     * guard and produces the event the caller persists.
     */
    public static TicketTriaged triage(
        TicketId ticketId,
        TicketStatus currentStatus,
        long currentVersion,
        TicketCategoryId categoryId,
        TicketSubcategoryId subcategoryId,
        TicketPriority priority,
        SupportQueueId supportQueueId,
        String triagedByActorType,
        String triagedByActorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        Objects.requireNonNull(supportQueueId, "supportQueueId must not be null");
        Objects.requireNonNull(triagedByActorType, "triagedByActorType must not be null");
        Objects.requireNonNull(triagedByActorId, "triagedByActorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (priority == TicketPriority.UNASSIGNED) {
            throw new IllegalArgumentException("priority must be an explicit value, not UNASSIGNED");
        }
        if (currentStatus != TicketStatus.NEW) {
            throw new InvalidTicketTransitionException(currentStatus, TicketStatus.NEW);
        }

        return new TicketTriaged(
            ticketId,
            currentStatus,
            TicketStatus.TRIAGED,
            categoryId,
            subcategoryId,
            priority,
            supportQueueId,
            triagedByActorType,
            triagedByActorId,
            currentVersion + 1,
            occurredAt
        );
    }

    public static final Set<TicketStatus> REASSIGNABLE_STATUSES = Set.of(
        TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_USER, TicketStatus.WAITING_FOR_APPROVAL
    );

    /** SPEC-TW-008 §2/§3: {@code TRIAGED, no assignee → ASSIGNED, assignee set}. */
    public static TicketAssigned assign(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String assigneeId,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(assigneeId, "assigneeId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.TRIAGED) {
            throw new InvalidTicketTransitionException(currentStatus, TicketStatus.TRIAGED);
        }
        if (currentAssigneeId != null) {
            throw new TicketAlreadyAssignedException();
        }

        return new TicketAssigned(
            ticketId, currentStatus, TicketStatus.ASSIGNED, assigneeId, actorType, actorId, reason,
            currentVersion + 1, occurredAt
        );
    }

    /** SPEC-TW-008 §2/§4: replaces the assignee, preserves status. */
    public static TicketReassigned reassign(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String newAssigneeId,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(newAssigneeId, "newAssigneeId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        if (!REASSIGNABLE_STATUSES.contains(currentStatus)) {
            throw new InvalidTicketStateException(currentStatus, REASSIGNABLE_STATUSES);
        }
        if (newAssigneeId.equals(currentAssigneeId)) {
            throw new ReassignmentRequiresDifferentAssigneeException();
        }

        return new TicketReassigned(
            ticketId, currentStatus, currentAssigneeId, newAssigneeId, actorType, actorId, reason,
            currentVersion + 1, occurredAt
        );
    }

    /** SPEC-TW-008 §2/§5: {@code ASSIGNED → TRIAGED}, assignee cleared. */
    public static TicketUnassigned unassign(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.ASSIGNED) {
            throw new InvalidTicketTransitionException(currentStatus, TicketStatus.ASSIGNED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketUnassigned(
            ticketId, currentStatus, TicketStatus.TRIAGED, currentAssigneeId, actorType, actorId, reason,
            currentVersion + 1, occurredAt
        );
    }

    public static TicketStatusChanged transitionStatus(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        TicketStatus targetStatus,
        String actorType,
        String actorId,
        String reason,
        Instant waitingForRequesterSince,
        String approvalReference,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        TransitionDescriptor descriptor = transitionDescriptor(currentStatus, targetStatus);
        Instant effectiveWaitingForRequesterSince = null;
        String effectiveApprovalReference = null;

        if (targetStatus == TicketStatus.WAITING_FOR_USER) {
            effectiveWaitingForRequesterSince = waitingForRequesterSince == null ? occurredAt : waitingForRequesterSince;
            if (approvalReference != null && !approvalReference.isBlank()) {
                throw new IllegalArgumentException("approvalReference is only allowed for WAITING_FOR_APPROVAL");
            }
        } else if (targetStatus == TicketStatus.WAITING_FOR_APPROVAL) {
            if (approvalReference == null || approvalReference.isBlank()) {
                throw new IllegalArgumentException("approvalReference is required for WAITING_FOR_APPROVAL");
            }
            effectiveApprovalReference = approvalReference;
            if (waitingForRequesterSince != null) {
                throw new IllegalArgumentException("waitingForRequesterSince is only allowed for WAITING_FOR_USER");
            }
        } else {
            if (waitingForRequesterSince != null || (approvalReference != null && !approvalReference.isBlank())) {
                throw new IllegalArgumentException("waiting metadata is only allowed for waiting statuses");
            }
        }

        return new TicketStatusChanged(
            ticketId, currentStatus, targetStatus, currentAssigneeId, actorType, actorId, reason,
            descriptor.transitionId(), descriptor.reasonCode(), effectiveWaitingForRequesterSince, effectiveApprovalReference,
            currentVersion + 1, occurredAt
        );
    }

    /** SPEC-TW-010 §2/§3: {@code IN_PROGRESS -> RESOLVED} (transitionId {@code SM-010}, reasonCode {@code TICKET_RESOLVED}). */
    public static TicketResolved resolve(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        long currentVersion,
        ResolutionCode resolutionCode,
        String resolutionSummary,
        Instant autoCloseDueAt,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(resolutionCode, "resolutionCode must not be null");
        Objects.requireNonNull(resolutionSummary, "resolutionSummary must not be null");
        Objects.requireNonNull(autoCloseDueAt, "autoCloseDueAt must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.IN_PROGRESS) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.RESOLVED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedSummary = resolutionSummary.trim();
        if (trimmedSummary.length() < 10 || trimmedSummary.length() > 5000) {
            throw new IllegalArgumentException("resolutionSummary must be 10 to 5000 characters once trimmed");
        }

        return new TicketResolved(
            ticketId, currentStatus, TicketStatus.RESOLVED, currentAssigneeId, currentResolutionCycleId,
            resolutionCode, trimmedSummary, actorType, actorId, occurredAt, autoCloseDueAt,
            "SM-010", "TICKET_RESOLVED", currentVersion + 1, occurredAt
        );
    }

    public static final Set<TicketStatus> REOPENABLE_STATUSES = Set.of(TicketStatus.RESOLVED, TicketStatus.CLOSED);

    /** SPEC-TW-011 §2: {@code RESOLVED -> CLOSED} (transitionId {@code SM-011}, reasonCode {@code TICKET_CLOSED}). */
    public static TicketClosed close(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        long currentVersion,
        CloseReasonCode closeReasonCode,
        String closeReason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(closeReasonCode, "closeReasonCode must not be null");
        Objects.requireNonNull(closeReason, "closeReason must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.RESOLVED) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.CLOSED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedReason = closeReason.trim();
        if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
            throw new IllegalArgumentException("closeReason must be 3 to 500 characters once trimmed");
        }

        return new TicketClosed(
            ticketId, currentStatus, TicketStatus.CLOSED, currentAssigneeId, currentResolutionCycleId,
            closeReasonCode, trimmedReason, actorType, actorId, occurredAt,
            "SM-011", "TICKET_CLOSED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-026 domain-rules: {@code RESOLVED -> CLOSED} (transitionId
     * {@code SM-031}, reasonCode {@code RESOLUTION_CONFIRMED}). Mirrors
     * {@link #close}'s (SPEC-TW-011) validation shape exactly — same
     * assignee/reason-length invariants, since a confirmed resolution is
     * still, physically, a close — but produces its own event identity so
     * "the requester/support actor confirmed this was fixed" stays
     * distinguishable from an administrative close in the timeline.
     */
    public static TicketResolutionConfirmed confirmResolution(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        long currentVersion,
        ResolutionConfirmationReasonCode confirmationReasonCode,
        String reason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(confirmationReasonCode, "confirmationReasonCode must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.RESOLVED) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.CLOSED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
            throw new IllegalArgumentException("reason must be 3 to 500 characters once trimmed");
        }

        return new TicketResolutionConfirmed(
            ticketId, currentStatus, TicketStatus.CLOSED, currentAssigneeId, currentResolutionCycleId,
            confirmationReasonCode, trimmedReason, actorType, actorId, occurredAt,
            "SM-031", "RESOLUTION_CONFIRMED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-027 domain-rules: {@code RESOLVED -> CLOSED} (transitionId
     * {@code SM-032}, reasonCode {@code TICKET_AUTO_CLOSED}). Mirrors
     * {@link #close}'s (SPEC-TW-011) assignee/reason-length invariants,
     * plus one auto-close-specific rule domain-rules calls out explicitly:
     * "the scheduler signal is advisory; the service recomputes
     * eligibility under lock" — {@code occurredAt} must not be before
     * {@code autoCloseDueAt}, checked here, in the pure domain method,
     * rather than trusting the caller's own timing.
     */
    public static TicketAutoClosed autoClose(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        long currentVersion,
        Instant autoCloseDueAt,
        String reason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(autoCloseDueAt, "autoCloseDueAt must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.RESOLVED) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.CLOSED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        if (occurredAt.isBefore(autoCloseDueAt)) {
            throw new AutoCloseNotYetDueException();
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
            throw new IllegalArgumentException("reason must be 3 to 500 characters once trimmed");
        }

        return new TicketAutoClosed(
            ticketId, currentStatus, TicketStatus.CLOSED, currentAssigneeId, currentResolutionCycleId,
            CloseReasonCode.AUTO_CLOSE_TIMEOUT, trimmedReason, actorType, actorId, occurredAt,
            "SM-032", "TICKET_AUTO_CLOSED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-029 domain-rules: {@code non-terminal mutable state ->
     * CANCELLED}. Unlike every other single- or dual-source transition in
     * this class, Cancel accepts six distinct source statuses (phase-08
     * implementation-plan §4's state table) — each gets its own
     * transitionId ({@code SM-033} through {@code SM-038}), mirroring
     * {@link #reopen}'s one-id-per-source-status shape, all sharing
     * reasonCode {@code TICKET_CANCELLED}. No assignee is required (unlike
     * {@link #close}/{@link #confirmResolution}/{@link #autoClose}) — a
     * ticket can be cancelled before it is ever assigned.
     */
    public static final Set<TicketStatus> CANCELLABLE_STATUSES = Set.of(
        TicketStatus.NEW, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_USER,
        TicketStatus.WAITING_FOR_APPROVAL, TicketStatus.VERIFYING, TicketStatus.RESOLVED
    );

    public static TicketCancelled cancel(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        long currentVersion,
        CancelReasonCode cancelReasonCode,
        String cancelReason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(cancelReasonCode, "cancelReasonCode must not be null");
        Objects.requireNonNull(cancelReason, "cancelReason must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!CANCELLABLE_STATUSES.contains(currentStatus)) {
            throw new InvalidTicketStateException(currentStatus, CANCELLABLE_STATUSES);
        }
        String trimmedReason = cancelReason.trim();
        if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
            throw new IllegalArgumentException("cancelReason must be 3 to 500 characters once trimmed");
        }

        return new TicketCancelled(
            ticketId, currentStatus, TicketStatus.CANCELLED, currentAssigneeId, currentResolutionCycleId,
            cancelReasonCode, trimmedReason, actorType, actorId, occurredAt,
            cancelTransitionId(currentStatus), "TICKET_CANCELLED", currentVersion + 1, occurredAt
        );
    }

    private static String cancelTransitionId(TicketStatus currentStatus) {
        return switch (currentStatus) {
            case NEW -> "SM-033";
            case IN_PROGRESS -> "SM-034";
            case WAITING_FOR_USER -> "SM-035";
            case WAITING_FOR_APPROVAL -> "SM-036";
            case VERIFYING -> "SM-037";
            case RESOLVED -> "SM-038";
            default -> throw new InvalidTicketStateException(currentStatus, CANCELLABLE_STATUSES);
        };
    }

    /**
     * SPEC-TW-030 domain-rules: {@code mutable non-terminal state -> same
     * lifecycle state} (transitionId {@code SM-039}, reasonCode {@code
     * TICKET_ASSIGNMENT_UPDATED}) — a pure ownership mutation (team,
     * Support Queue, and/or assignee) that never advances or regresses the
     * lifecycle status, and must not touch resolution evidence (domain-
     * rules: "must not rewrite resolution evidence" — this method never
     * even receives any resolution field). {@code newTeamId}/{@code
     * newSupportQueueId} are always required (the Application layer
     * resolves {@code newTeamId} from the target queue's own catalog
     * entry, so the two can never disagree); {@code newAssigneeId} is
     * nullable — routing to a queue without a specific owner yet is valid.
     */
    public static final Set<TicketStatus> ASSIGNABLE_STATUSES = Set.of(
        TicketStatus.NEW, TicketStatus.TRIAGED, TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS,
        TicketStatus.WAITING_FOR_USER, TicketStatus.WAITING_FOR_APPROVAL, TicketStatus.EXECUTING,
        TicketStatus.VERIFYING, TicketStatus.RESOLVED, TicketStatus.ESCALATED, TicketStatus.FAILED
    );

    /**
     * SPEC-TW-031 domain-rules: mutable non-terminal states may enter
     * ESCALATED, but already-terminal states and ESCALATED itself cannot be
     * escalated again by this command.
     */
    public static final Set<TicketStatus> ESCALATABLE_STATUSES = Set.of(
        TicketStatus.NEW, TicketStatus.TRIAGED, TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS,
        TicketStatus.WAITING_FOR_USER, TicketStatus.WAITING_FOR_APPROVAL, TicketStatus.EXECUTING,
        TicketStatus.VERIFYING, TicketStatus.RESOLVED
    );

    /**
     * Mirrors every CHECK constraint requiring a non-null {@code
     * current_support_user_id}: V014's {@code ck_tickets_assigned_fields}
     * (ASSIGNED), V015's {@code ck_tickets_work_states_have_assignee}
     * (IN_PROGRESS/WAITING_FOR_USER/WAITING_FOR_APPROVAL), and V016's
     * {@code ck_tickets_resolved_fields} (RESOLVED).
     */
    private static final Set<TicketStatus> STATUSES_REQUIRING_ASSIGNEE = Set.of(
        TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_USER,
        TicketStatus.WAITING_FOR_APPROVAL, TicketStatus.RESOLVED
    );

    public static TicketAssignmentUpdated updateAssignment(
        TicketId ticketId,
        TicketStatus currentStatus,
        long currentVersion,
        String currentTeamId,
        SupportQueueId currentSupportQueueId,
        String currentAssigneeId,
        String newTeamId,
        SupportQueueId newSupportQueueId,
        String newAssigneeId,
        String reason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(newTeamId, "newTeamId must not be null");
        Objects.requireNonNull(newSupportQueueId, "newSupportQueueId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!ASSIGNABLE_STATUSES.contains(currentStatus)) {
            throw new InvalidTicketStateException(currentStatus, ASSIGNABLE_STATUSES);
        }
        boolean teamChanged = !newTeamId.equals(currentTeamId);
        boolean queueChanged = !newSupportQueueId.equals(currentSupportQueueId);
        boolean assigneeChanged = !Objects.equals(newAssigneeId, currentAssigneeId);
        if (!teamChanged && !queueChanged && !assigneeChanged) {
            throw new AssignmentRequiresAChangeException();
        }
        if (newAssigneeId == null && STATUSES_REQUIRING_ASSIGNEE.contains(currentStatus)) {
            throw new AssigneeRequiredForCurrentStatusException();
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
            throw new IllegalArgumentException("reason must be 3 to 500 characters once trimmed");
        }

        return new TicketAssignmentUpdated(
            ticketId, currentStatus, currentStatus, currentTeamId, newTeamId, currentSupportQueueId, newSupportQueueId,
            currentAssigneeId, newAssigneeId, trimmedReason, actorType, actorId, occurredAt,
            "SM-039", "TICKET_ASSIGNMENT_UPDATED", currentVersion + 1, occurredAt
        );
    }

    public static TicketEscalated escalate(
        TicketId ticketId,
        TicketStatus currentStatus,
        long currentVersion,
        String currentTeamId,
        SupportQueueId currentSupportQueueId,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        String activeWorkflowId,
        EscalationReasonCode escalationReasonCode,
        String escalationReason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(escalationReasonCode, "escalationReasonCode must not be null");
        Objects.requireNonNull(escalationReason, "escalationReason must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!ESCALATABLE_STATUSES.contains(currentStatus)) {
            throw new InvalidTicketStateException(currentStatus, ESCALATABLE_STATUSES);
        }
        String trimmedReason = escalationReason.trim();
        if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
            throw new IllegalArgumentException("escalationReason must be 3 to 500 characters once trimmed");
        }

        return new TicketEscalated(
            ticketId, currentStatus, TicketStatus.ESCALATED, currentTeamId, currentSupportQueueId, currentAssigneeId,
            currentResolutionCycleId, activeWorkflowId, escalationReasonCode, trimmedReason, actorType, actorId,
            occurredAt, escalationTransitionId(currentStatus), "TICKET_ESCALATED", currentVersion + 1, occurredAt
        );
    }

    private static String escalationTransitionId(TicketStatus currentStatus) {
        return switch (currentStatus) {
            case NEW -> "SM-040";
            case TRIAGED -> "SM-041";
            case ASSIGNED -> "SM-042";
            case IN_PROGRESS -> "SM-043";
            case WAITING_FOR_USER -> "SM-044";
            case WAITING_FOR_APPROVAL -> "SM-045";
            case EXECUTING -> "SM-046";
            case VERIFYING -> "SM-047";
            case RESOLVED -> "SM-048";
            default -> throw new InvalidTicketStateException(currentStatus, ESCALATABLE_STATUSES);
        };
    }

    /**
     * SPEC-TW-032 domain-rules: {@code ESCALATED -> IN_PROGRESS}
     * (transitionId {@code SM-049}, reasonCode {@code
     * TICKET_ESCALATION_RESUMED}) — the single-source-status counterpart to
     * {@link #escalate}. {@code ownershipStatus} mirrors {@link #reopen}'s
     * same parameter exactly: supplied by the caller (already resolved via
     * the Support Agent Directory), never computed here, and never blocks
     * the resume itself — "Resume must select a next owner/queue" is
     * satisfied by reporting the current owner's standing, not by silently
     * reassigning. Neither {@code current_resolution_cycle_id} nor {@code
     * escalation_reason_code}/{@code escalated_at}/{@code escalated_by} are
     * touched — domain-rules: "cannot discard the escalation resolution
     * notes."
     */
    public static TicketEscalationResumed resumeEscalation(
        TicketId ticketId,
        TicketStatus currentStatus,
        long currentVersion,
        String currentTeamId,
        SupportQueueId currentSupportQueueId,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        EscalationResumeReasonCode resumeReasonCode,
        String resumeReason,
        OwnershipStatus ownershipStatus,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(resumeReasonCode, "resumeReasonCode must not be null");
        Objects.requireNonNull(resumeReason, "resumeReason must not be null");
        Objects.requireNonNull(ownershipStatus, "ownershipStatus must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.ESCALATED) {
            throw new InvalidTicketTransitionException(currentStatus, TicketStatus.ESCALATED);
        }
        String trimmedReason = resumeReason.trim();
        if (trimmedReason.length() < 3 || trimmedReason.length() > 500) {
            throw new IllegalArgumentException("resumeReason must be 3 to 500 characters once trimmed");
        }

        return new TicketEscalationResumed(
            ticketId, TicketStatus.ESCALATED, TicketStatus.IN_PROGRESS, currentTeamId, currentSupportQueueId,
            currentAssigneeId, currentResolutionCycleId, resumeReasonCode, trimmedReason, actorType, actorId,
            occurredAt, ownershipStatus, "SM-049", "TICKET_ESCALATION_RESUMED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-011 §3: {@code RESOLVED -> IN_PROGRESS} (transitionId {@code
     * SM-012}) or {@code CLOSED -> IN_PROGRESS} (transitionId {@code
     * SM-013}). {@code ownershipStatus} is supplied by the caller (the
     * Application layer already resolved it via the Support Agent
     * Directory — an I/O lookup this pure method must not perform).
     */
    public static TicketReopened reopen(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        int currentResolutionCycleNumber,
        UUID newResolutionCycleId,
        long currentVersion,
        int currentReopenCount,
        ReopenReasonCode reopenReasonCode,
        String reopenReason,
        OwnershipStatus ownershipStatus,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(newResolutionCycleId, "newResolutionCycleId must not be null");
        Objects.requireNonNull(reopenReasonCode, "reopenReasonCode must not be null");
        Objects.requireNonNull(reopenReason, "reopenReason must not be null");
        Objects.requireNonNull(ownershipStatus, "ownershipStatus must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!REOPENABLE_STATUSES.contains(currentStatus)) {
            throw new InvalidTicketStateException(currentStatus, REOPENABLE_STATUSES);
        }
        String trimmedReason = reopenReason.trim();
        if (trimmedReason.length() < 10 || trimmedReason.length() > 1000) {
            throw new IllegalArgumentException("reopenReason must be 10 to 1000 characters once trimmed");
        }

        String transitionId = currentStatus == TicketStatus.RESOLVED ? "SM-012" : "SM-013";

        return new TicketReopened(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, currentAssigneeId, currentResolutionCycleId,
            newResolutionCycleId, currentResolutionCycleNumber + 1, reopenReasonCode, trimmedReason,
            actorType, actorId, occurredAt, currentReopenCount + 1, ownershipStatus,
            transitionId, "TICKET_REOPENED", currentVersion + 1, occurredAt
        );
    }

    /** SPEC-TW-012 §1: {@code IN_PROGRESS -> WAITING_FOR_USER} (transitionId {@code SM-014}, reasonCode {@code USER_INPUT_REQUIRED}). */
    public static TicketUserInputRequested requestUserInput(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID requestId,
        String prompt,
        List<String> requestedFields,
        Instant expiresAt,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.IN_PROGRESS) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.WAITING_FOR_USER);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedPrompt = prompt.trim();
        if (trimmedPrompt.length() < 10 || trimmedPrompt.length() > 2000) {
            throw new IllegalArgumentException("prompt must be 10 to 2000 characters once trimmed");
        }

        return new TicketUserInputRequested(
            ticketId, currentStatus, TicketStatus.WAITING_FOR_USER, currentAssigneeId, requestId, trimmedPrompt,
            requestedFields, actorType, actorId, occurredAt, occurredAt, "IN_PROGRESS", expiresAt,
            "SM-014", "USER_INPUT_REQUIRED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-013 §1: {@code WAITING_FOR_USER -> IN_PROGRESS} (transitionId
     * {@code SM-015}, reasonCode {@code USER_REPLIED}). The Application
     * layer only invokes this once it has already confirmed the reply
     * targets the ticket's current {@code OPEN} user input request — this
     * method's own status check is a defensive invariant, not the primary
     * gate (that decision needs the request row, which this pure method
     * must not query).
     */
    public static TicketUserInputResumed resumeFromUserReply(
        TicketId ticketId,
        TicketStatus currentStatus,
        long currentVersion,
        UUID requestId,
        UUID messageId,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.WAITING_FOR_USER) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.IN_PROGRESS);
        }

        return new TicketUserInputResumed(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, requestId, messageId, actorType, actorId, occurredAt,
            "SM-015", "USER_REPLIED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-014 domain-rules §1: {@code IN_PROGRESS -> WAITING_FOR_APPROVAL}
     * (transitionId {@code SM-016}, reasonCode {@code APPROVAL_REQUIRED}).
     * Distinct from the generic {@link #transitionStatus} path used by
     * SPEC-TW-009 (transitionId {@code SM-007} for the same status pair,
     * with a client-supplied {@code approvalReference}): this command binds
     * ticket, workflow, action, and a risk-context snapshot, and the
     * {@code approvalId} is generated by the Application layer rather than
     * accepted from the client, per domain-rules §1's "clients cannot spoof
     * approver or approval decision".
     */
    public static TicketApprovalWaitStarted requestApproval(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID approvalRequestId,
        String approvalId,
        String workflowId,
        String actionId,
        String actionType,
        ApprovalRiskLevel riskLevel,
        Map<String, Object> riskContext,
        String reason,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.IN_PROGRESS) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.WAITING_FOR_APPROVAL);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedWorkflowId = workflowId.trim();
        String trimmedActionId = actionId.trim();
        String trimmedActionType = actionType.trim();
        if (trimmedWorkflowId.isEmpty() || trimmedActionId.isEmpty() || trimmedActionType.isEmpty()) {
            throw new IllegalArgumentException("workflowId, actionId, and actionType must not be blank");
        }
        if (riskContext == null || riskContext.isEmpty()) {
            throw new IllegalArgumentException("riskContext must not be empty");
        }

        return new TicketApprovalWaitStarted(
            ticketId, currentStatus, TicketStatus.WAITING_FOR_APPROVAL, currentAssigneeId, approvalRequestId, approvalId,
            trimmedWorkflowId, trimmedActionId, trimmedActionType, riskLevel, riskContext,
            reason == null ? null : reason.trim(), actorType, actorId, occurredAt,
            "SM-016", "APPROVAL_REQUIRED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-015 domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS}
     * (transitionId {@code SM-017}, reasonCode {@code APPROVAL_GRANTED}).
     * Consumer-side classification (duplicate/stale/reference-mismatch/
     * expired-at-grant-time) happens in the Application layer, against the
     * approval-request row, before this method is ever invoked — this pure
     * method only re-asserts the ticket-level invariant that the Approval
     * Service's trust boundary already depends on: the ticket must still be
     * {@code WAITING_FOR_APPROVAL} when the grant is applied.
     */
    public static TicketApprovalGrantedApplied applyApprovalGranted(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID approvalRequestId,
        String approvalId,
        String workflowId,
        String actionId,
        String actionType,
        String approvedBy,
        Instant approvedAt,
        String authorizationReference,
        String grantedEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(grantedEventId, "grantedEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.WAITING_FOR_APPROVAL) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.IN_PROGRESS);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketApprovalGrantedApplied(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, currentAssigneeId, approvalRequestId, approvalId,
            workflowId, actionId, actionType, approvedBy, approvedAt, authorizationReference, grantedEventId,
            "SM-017", "APPROVAL_GRANTED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-016 domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS}
     * (transitionId {@code SM-018}, reasonCode {@code APPROVAL_REJECTED}).
     * Mirrors {@link #applyApprovalGranted}'s shape: consumer-side
     * classification (duplicate/stale/reference-mismatch/business-rule)
     * happens in the Application layer against the approval-request row
     * before this method is invoked. Rejected approval is not reusable —
     * this method only re-asserts the ticket-level invariant and clears the
     * ticket back to {@code IN_PROGRESS}; any future execution requires a
     * brand new action and a brand new approval request.
     */
    public static TicketApprovalRejectedApplied applyApprovalRejected(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID approvalRequestId,
        String approvalId,
        String workflowId,
        String actionId,
        String actionType,
        String rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        String rejectedEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(rejectedAt, "rejectedAt must not be null");
        Objects.requireNonNull(rejectionReason, "rejectionReason must not be null");
        Objects.requireNonNull(rejectedEventId, "rejectedEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.WAITING_FOR_APPROVAL) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.IN_PROGRESS);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketApprovalRejectedApplied(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, currentAssigneeId, approvalRequestId, approvalId,
            workflowId, actionId, actionType, rejectedBy, rejectedAt, rejectionReason, rejectedEventId,
            "SM-018", "APPROVAL_REJECTED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-017 domain-rules §1: {@code WAITING_FOR_APPROVAL -> IN_PROGRESS}
     * (transitionId {@code SM-019}, reasonCode {@code APPROVAL_EXPIRED}).
     * Mirrors {@link #applyApprovalRejected}'s shape. Expired approval can
     * never authorize execution; this method only re-asserts the
     * ticket-level invariant and clears the ticket back to {@code
     * IN_PROGRESS} — later execution requires a new approval request or a
     * new auto-approved policy decision.
     */
    public static TicketApprovalExpiredApplied applyApprovalExpired(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID approvalRequestId,
        String approvalId,
        String workflowId,
        String actionId,
        String actionType,
        Instant expiredAt,
        String expirationReason,
        String expiredEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(expiredAt, "expiredAt must not be null");
        Objects.requireNonNull(expirationReason, "expirationReason must not be null");
        Objects.requireNonNull(expiredEventId, "expiredEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.WAITING_FOR_APPROVAL) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.IN_PROGRESS);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketApprovalExpiredApplied(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, currentAssigneeId, approvalRequestId, approvalId,
            workflowId, actionId, actionType, expiredAt, expirationReason, expiredEventId,
            "SM-019", "APPROVAL_EXPIRED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-018 domain-rules §1: {@code IN_PROGRESS -> IN_PROGRESS}
     * (transitionId {@code SM-020}, reasonCode {@code AUTO_APPROVAL_APPLIED}).
     * Unlike {@link #applyApprovalGranted}/{@link #applyApprovalRejected}/
     * {@link #applyApprovalExpired}, this never follows a SPEC-TW-014
     * request-approval — auto-approval means the policy engine decided the
     * action never needed to pause the ticket at all, so there is no prior
     * {@code WAITING_FOR_APPROVAL} to leave. The ticket must still be
     * {@code IN_PROGRESS} and assigned; the self-transition is a real
     * recorded event (its own status-history row and version bump), not a
     * no-op.
     */
    public static TicketAutoApprovalApplied applyAutoApprovedPolicy(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        UUID approvalRequestId,
        String workflowId,
        String actionId,
        String actionType,
        ApprovalRiskLevel riskLevel,
        String policyId,
        String policyVersion,
        String policyDecisionId,
        String authorizationReference,
        Instant decidedAt,
        String autoApprovalEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(policyId, "policyId must not be null");
        Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        Objects.requireNonNull(policyDecisionId, "policyDecisionId must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(autoApprovalEventId, "autoApprovalEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.IN_PROGRESS) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.IN_PROGRESS);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketAutoApprovalApplied(
            ticketId, currentStatus, TicketStatus.IN_PROGRESS, currentAssigneeId, approvalRequestId,
            workflowId, actionId, actionType, riskLevel, policyId, policyVersion, policyDecisionId,
            authorizationReference, decidedAt, autoApprovalEventId,
            "SM-020", "AUTO_APPROVAL_APPLIED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-019 domain-rules §1: {@code EXECUTING -> VERIFYING} (transitionId
     * {@code SM-021}, reasonCode {@code TOOL_EXECUTION_COMPLETED}). Mirrors
     * {@link #applyApprovalGranted}'s shape: consumer-side classification
     * (duplicate/stale/reference-mismatch against the authorization on
     * record) happens in the Application layer, against the ticket +
     * authorizing approval-request projection, before this method is ever
     * invoked — this pure method only re-asserts the ticket-level invariant
     * the Tool Gateway's trust boundary already depends on: the ticket must
     * still be {@code EXECUTING}. Tool success never resolves the ticket;
     * only Phase 07 Verification can do that, so the target status here is
     * always {@code VERIFYING}.
     */
    public static TicketToolExecutionCompletedApplied applyToolExecutionCompleted(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String workflowId,
        String actionId,
        String authorizationReference,
        String toolExecutionId,
        String toolResultId,
        Instant completedAt,
        Map<String, Object> resultSummary,
        String completedEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(toolExecutionId, "toolExecutionId must not be null");
        Objects.requireNonNull(toolResultId, "toolResultId must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(completedEventId, "completedEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.EXECUTING) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.VERIFYING);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketToolExecutionCompletedApplied(
            ticketId, currentStatus, TicketStatus.VERIFYING, currentAssigneeId, workflowId, actionId,
            authorizationReference, toolExecutionId, toolResultId, completedAt, resultSummary, completedEventId,
            "SM-021", "TOOL_EXECUTION_COMPLETED", currentVersion + 1, occurredAt
        );
    }

    private static final Set<String> SAFE_FAILURE_CLASSES = Set.of("KNOWN_SAFE", "RETRYABLE_SAFE");

    /**
     * SPEC-TW-020 domain-rules §1: {@code EXECUTING -> IN_PROGRESS}
     * (transitionId {@code SM-022}, reasonCode {@code
     * TOOL_EXECUTION_FAILED_SAFE}) when {@code failureClass} is {@code
     * KNOWN_SAFE} or {@code RETRYABLE_SAFE} — the tool created no unknown
     * external side effect, so work can safely continue or be replanned —
     * or {@code EXECUTING -> FAILED} (transitionId {@code SM-023},
     * reasonCode {@code TOOL_EXECUTION_PIPELINE_FAILED}) when it is {@code
     * PIPELINE_FAILED} — the execution pipeline itself failed and needs
     * explicit recovery. {@code UNKNOWN_SIDE_EFFECT} is never valid here
     * (SPEC-TW-021's {@code tool.execution.result_unknown.v1} owns that
     * case); the consumed-event schema already excludes it from {@code
     * failureClass}'s enum, so reaching the {@code default} branch below
     * would mean a validated event slipped an invariant the Application
     * layer is supposed to guarantee before ever calling this method.
     * Mirrors {@link #applyToolExecutionCompleted}'s shape otherwise:
     * consumer-side classification (duplicate/stale/reference-mismatch)
     * happens in the Application layer before this method is invoked.
     */
    public static TicketToolExecutionFailedApplied applyToolExecutionFailed(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String workflowId,
        String actionId,
        String authorizationReference,
        String toolExecutionId,
        String failureCode,
        String failureClass,
        Instant failedAt,
        Boolean safeToRetry,
        String failedEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(toolExecutionId, "toolExecutionId must not be null");
        Objects.requireNonNull(failureCode, "failureCode must not be null");
        Objects.requireNonNull(failureClass, "failureClass must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        Objects.requireNonNull(failedEventId, "failedEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        TicketStatus targetStatus;
        String transitionId;
        String reasonCode;
        if (SAFE_FAILURE_CLASSES.contains(failureClass)) {
            targetStatus = TicketStatus.IN_PROGRESS;
            transitionId = "SM-022";
            reasonCode = "TOOL_EXECUTION_FAILED_SAFE";
        } else if ("PIPELINE_FAILED".equals(failureClass)) {
            targetStatus = TicketStatus.FAILED;
            transitionId = "SM-023";
            reasonCode = "TOOL_EXECUTION_PIPELINE_FAILED";
        } else {
            throw new IllegalArgumentException("unsupported failureClass for tool.execution.failed: " + failureClass);
        }

        if (currentStatus != TicketStatus.EXECUTING) {
            throw new InvalidStatusTransitionException(currentStatus, targetStatus);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketToolExecutionFailedApplied(
            ticketId, currentStatus, targetStatus, currentAssigneeId, workflowId, actionId, authorizationReference,
            toolExecutionId, failureCode, failureClass, failedAt, safeToRetry, failedEventId,
            transitionId, reasonCode, currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-021 domain-rules §1: {@code EXECUTING -> ESCALATED}
     * (transitionId {@code SM-024}, reasonCode {@code TOOL_RESULT_UNKNOWN}).
     * An unknown result is a safety boundary — the tool may or may not have
     * produced an external side effect, so this transition never targets
     * {@code IN_PROGRESS} or any other status the way {@link
     * #applyToolExecutionFailed}'s known-safe branch does; the ticket
     * always escalates, and {@code reconciliationRequired} is always
     * {@code true} (there is no "known unknown" that skips reconciliation).
     * Mirrors {@link #applyToolExecutionCompleted}/{@link
     * #applyToolExecutionFailed}'s shape otherwise: consumer-side
     * classification (duplicate/stale/conflict-with-an-already-recorded
     * outcome) happens in the Application layer before this method is
     * invoked.
     */
    public static TicketToolResultUnknownRecorded applyToolResultUnknown(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String workflowId,
        String actionId,
        String authorizationReference,
        String toolExecutionId,
        String unknownReason,
        List<String> evidenceReferences,
        Instant observedAt,
        String recordedEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(authorizationReference, "authorizationReference must not be null");
        Objects.requireNonNull(toolExecutionId, "toolExecutionId must not be null");
        Objects.requireNonNull(unknownReason, "unknownReason must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(recordedEventId, "recordedEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.EXECUTING) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.ESCALATED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketToolResultUnknownRecorded(
            ticketId, currentStatus, TicketStatus.ESCALATED, currentAssigneeId, workflowId, actionId, authorizationReference,
            toolExecutionId, unknownReason, evidenceReferences, observedAt, true, recordedEventId,
            "SM-024", "TOOL_RESULT_UNKNOWN", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-022 domain-rules §1: {@code VERIFYING -> VERIFYING}
     * (transitionId {@code SM-025}, reasonCode {@code VERIFICATION_STARTED}).
     * Mirrors {@link #applyAutoApprovedPolicy}'s self-transition shape
     * (SPEC-TW-018): the ticket must still be {@code VERIFYING} and
     * assigned; the Application layer has already validated that {@code
     * toolResultId} belongs to this ticket and computed {@code
     * attemptNumber} and {@code resolutionCycleId} — I/O this pure method
     * must not perform.
     */
    public static TicketVerificationStarted startVerification(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String verificationId,
        UUID resolutionCycleId,
        String workflowId,
        String toolResultId,
        int attemptNumber,
        String verificationType,
        String reason,
        String startedByType,
        String startedById,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(toolResultId, "toolResultId must not be null");
        Objects.requireNonNull(verificationType, "verificationType must not be null");
        Objects.requireNonNull(startedByType, "startedByType must not be null");
        Objects.requireNonNull(startedById, "startedById must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.VERIFYING) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.VERIFYING);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketVerificationStarted(
            ticketId, currentStatus, TicketStatus.VERIFYING, currentAssigneeId, verificationId, resolutionCycleId,
            workflowId, toolResultId, attemptNumber, verificationType, reason == null ? null : reason.trim(),
            startedByType, startedById, occurredAt, "SM-025", "VERIFICATION_STARTED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-023 domain-rules §1: {@code VERIFYING -> VERIFYING}
     * (transitionId {@code SM-026}, reasonCode {@code
     * VERIFICATION_SUCCEEDED}). Mirrors {@link #startVerification}'s
     * self-transition shape (SPEC-TW-022): the ticket must still be {@code
     * VERIFYING} and assigned; the Application layer has already validated
     * that {@code verificationId} references the current active attempt,
     * bound to the current workflow, resolution cycle, and attempt number,
     * before this method is ever invoked. Successful verification never
     * resolves the ticket by itself — that is SPEC-TW-025's job, given this
     * event's stored evidence.
     */
    public static TicketVerificationSuccessApplied applyVerificationSuccess(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String verificationId,
        String workflowId,
        UUID resolutionCycleId,
        int attemptNumber,
        String verificationEvidenceId,
        Map<String, Object> evidenceSummary,
        Instant completedAt,
        String completedEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(verificationEvidenceId, "verificationEvidenceId must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(completedEventId, "completedEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.VERIFYING) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.VERIFYING);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketVerificationSuccessApplied(
            ticketId, currentStatus, TicketStatus.VERIFYING, currentAssigneeId, verificationId, workflowId,
            resolutionCycleId, attemptNumber, verificationEvidenceId, evidenceSummary, completedAt, completedEventId,
            "SM-026", "VERIFICATION_SUCCEEDED", currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-024 domain-rules §1: {@code VERIFYING -> IN_PROGRESS}
     * (transitionId {@code SM-027}, reasonCode {@code
     * VERIFICATION_FAILED_RETRYABLE}), {@code VERIFYING -> ESCALATED}
     * (transitionId {@code SM-028}, reasonCode {@code
     * VERIFICATION_FAILED_LIMIT_OR_UNSAFE}), or {@code VERIFYING -> FAILED}
     * (transitionId {@code SM-029}, reasonCode {@code
     * VERIFICATION_PIPELINE_FAILED}). {@code unsafeResult} always wins —
     * "unsafe result enters ESCALATED" is unconditional, checked before
     * {@code failureClass} — followed by {@code PIPELINE_FAILED} (the
     * verification pipeline itself broke, unrelated to retry counting),
     * then {@code RETRYABLE}, which only returns to {@code IN_PROGRESS}
     * while {@code hasReachedFailureLimit} is {@code false}; once the
     * Application layer's own count of prior {@code FAILED} attempts in
     * this resolution cycle reaches the limit ("the third failure or
     * unsafe result escalates" — I/O this pure method must not perform),
     * even a retryable failure escalates instead.
     */
    public static TicketVerificationFailureApplied applyVerificationFailure(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        long currentVersion,
        String verificationId,
        String workflowId,
        UUID resolutionCycleId,
        int attemptNumber,
        String failureCode,
        String failureClass,
        boolean unsafeResult,
        boolean hasReachedFailureLimit,
        Instant failedAt,
        String failedEventId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(resolutionCycleId, "resolutionCycleId must not be null");
        Objects.requireNonNull(failureCode, "failureCode must not be null");
        Objects.requireNonNull(failureClass, "failureClass must not be null");
        Objects.requireNonNull(failedAt, "failedAt must not be null");
        Objects.requireNonNull(failedEventId, "failedEventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        TicketStatus targetStatus;
        String transitionId;
        String reasonCode;
        if (unsafeResult) {
            targetStatus = TicketStatus.ESCALATED;
            transitionId = "SM-028";
            reasonCode = "VERIFICATION_FAILED_LIMIT_OR_UNSAFE";
        } else if ("PIPELINE_FAILED".equals(failureClass)) {
            targetStatus = TicketStatus.FAILED;
            transitionId = "SM-029";
            reasonCode = "VERIFICATION_PIPELINE_FAILED";
        } else if ("RETRYABLE".equals(failureClass)) {
            if (hasReachedFailureLimit) {
                targetStatus = TicketStatus.ESCALATED;
                transitionId = "SM-028";
                reasonCode = "VERIFICATION_FAILED_LIMIT_OR_UNSAFE";
            } else {
                targetStatus = TicketStatus.IN_PROGRESS;
                transitionId = "SM-027";
                reasonCode = "VERIFICATION_FAILED_RETRYABLE";
            }
        } else {
            throw new IllegalArgumentException("unsupported failureClass for verification.failed: " + failureClass);
        }

        if (currentStatus != TicketStatus.VERIFYING) {
            throw new InvalidStatusTransitionException(currentStatus, targetStatus);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }

        return new TicketVerificationFailureApplied(
            ticketId, currentStatus, targetStatus, currentAssigneeId, verificationId, workflowId, resolutionCycleId,
            attemptNumber, failureCode, failureClass, unsafeResult, failedAt, failedEventId,
            transitionId, reasonCode, currentVersion + 1, occurredAt
        );
    }

    /**
     * SPEC-TW-025 domain-rules §1: {@code VERIFYING -> RESOLVED} (transitionId
     * {@code SM-030}, reasonCode {@code VERIFIED_RESOLUTION}). Mirrors
     * {@link #resolve}'s (SPEC-TW-010) resolution-code/summary validation
     * exactly, but the source status is {@code VERIFYING}, not {@code
     * IN_PROGRESS}, and this transition additionally carries {@code
     * verificationId}/{@code verificationEvidenceId} — the Application layer
     * has already validated (SPEC-TW-025 §3) that this evidence is trusted,
     * current, and successful, bound to this ticket's current resolution
     * cycle, before this method is ever invoked. Tool success, a proposal, or
     * human judgment alone can never substitute for this evidence.
     */
    public static TicketResolvedWithVerification resolveWithVerification(
        TicketId ticketId,
        TicketStatus currentStatus,
        String currentAssigneeId,
        UUID currentResolutionCycleId,
        long currentVersion,
        String verificationId,
        String verificationEvidenceId,
        ResolutionCode resolutionCode,
        String resolutionSummary,
        Instant autoCloseDueAt,
        String actorType,
        String actorId,
        Instant occurredAt
    ) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(currentStatus, "currentStatus must not be null");
        Objects.requireNonNull(currentResolutionCycleId, "currentResolutionCycleId must not be null");
        Objects.requireNonNull(verificationId, "verificationId must not be null");
        Objects.requireNonNull(verificationEvidenceId, "verificationEvidenceId must not be null");
        Objects.requireNonNull(resolutionCode, "resolutionCode must not be null");
        Objects.requireNonNull(resolutionSummary, "resolutionSummary must not be null");
        Objects.requireNonNull(autoCloseDueAt, "autoCloseDueAt must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (currentStatus != TicketStatus.VERIFYING) {
            throw new InvalidStatusTransitionException(currentStatus, TicketStatus.RESOLVED);
        }
        if (currentAssigneeId == null) {
            throw new TicketNotAssignedException();
        }
        String trimmedSummary = resolutionSummary.trim();
        if (trimmedSummary.length() < 10 || trimmedSummary.length() > 5000) {
            throw new IllegalArgumentException("resolutionSummary must be 10 to 5000 characters once trimmed");
        }

        return new TicketResolvedWithVerification(
            ticketId, currentStatus, TicketStatus.RESOLVED, currentAssigneeId, currentResolutionCycleId,
            verificationId, verificationEvidenceId, resolutionCode, trimmedSummary, actorType, actorId, occurredAt,
            autoCloseDueAt, "SM-030", "VERIFIED_RESOLUTION", currentVersion + 1, occurredAt
        );
    }

    private static TransitionDescriptor transitionDescriptor(TicketStatus currentStatus, TicketStatus targetStatus) {
        if (currentStatus == TicketStatus.ASSIGNED && targetStatus == TicketStatus.IN_PROGRESS) {
            return new TransitionDescriptor("SM-005", "WORK_STARTED");
        }
        if (currentStatus == TicketStatus.IN_PROGRESS && targetStatus == TicketStatus.WAITING_FOR_USER) {
            return new TransitionDescriptor("SM-006", "WAITING_FOR_USER");
        }
        if (currentStatus == TicketStatus.IN_PROGRESS && targetStatus == TicketStatus.WAITING_FOR_APPROVAL) {
            return new TransitionDescriptor("SM-007", "WAITING_FOR_APPROVAL");
        }
        if (currentStatus == TicketStatus.WAITING_FOR_USER && targetStatus == TicketStatus.IN_PROGRESS) {
            return new TransitionDescriptor("SM-008", "WORK_RESUMED");
        }
        if (currentStatus == TicketStatus.WAITING_FOR_APPROVAL && targetStatus == TicketStatus.IN_PROGRESS) {
            return new TransitionDescriptor("SM-009", "WORK_RESUMED");
        }
        throw new InvalidStatusTransitionException(currentStatus, targetStatus);
    }

    private record TransitionDescriptor(String transitionId, String reasonCode) {
    }

    public List<TicketDomainEvent> pullDomainEvents() {
        List<TicketDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    public TicketId id() {
        return id;
    }

    public TicketDisplayId displayId() {
        return displayId;
    }

    public RequesterId requesterId() {
        return requesterId;
    }

    public TicketTitle title() {
        return title;
    }

    public TicketDescription description() {
        return description;
    }

    public ApplicationCode applicationCode() {
        return applicationCode;
    }

    public TicketSource source() {
        return source;
    }

    public String category() {
        return category;
    }

    public String subcategory() {
        return subcategory;
    }

    public TicketPriority priority() {
        return priority;
    }

    public TicketStatus status() {
        return status;
    }

    public String currentTeamId() {
        return currentTeamId;
    }

    public String currentSupportUserId() {
        return currentSupportUserId;
    }

    public String activeWorkflowId() {
        return activeWorkflowId;
    }

    public UUID currentResolutionCycleId() {
        return currentResolutionCycleId;
    }

    public Instant autoCloseDueAt() {
        return autoCloseDueAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public String cancelReasonCode() {
        return cancelReasonCode;
    }

    public String closeReasonCode() {
        return closeReasonCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public String createdByType() {
        return createdByType;
    }

    public String createdById() {
        return createdById;
    }
}
