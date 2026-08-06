package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketVerificationFailureAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyVerificationFailureUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationAttemptGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationFailureUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationFailureApplied;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SPEC-TW-024 §1/domain-rules §1: {@code VERIFYING -> IN_PROGRESS}/{@code
 * ESCALATED}/{@code FAILED}. Mirrors {@code
 * ApplyVerificationSuccessApplicationService}'s (SPEC-TW-023) three-way
 * existing-state classification and reference-matching shape exactly: no
 * attempt found is {@code STALE}; an already-{@code FAILED} attempt is
 * {@code DUPLICATE} (every sub-outcome — retryable, escalated, or pipeline
 * — marks the attempt row {@code FAILED}, so a replay always lands here
 * regardless of which branch first applied); an attempt in a different
 * terminal state ({@code SUCCEEDED}/{@code STALE}/{@code CONFLICT}) is
 * {@code CONFLICT_REQUIRES_RECONCILIATION} ("Conflicting success enters
 * reconciliation"). Only an {@code ACTIVE} attempt proceeds to the full
 * reference match, then the failure-limit count that {@link
 * Ticket#applyVerificationFailure} needs to classify retryable-vs-escalated.
 */
@Service
public class ApplyVerificationFailureApplicationService implements ApplyVerificationFailureUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyVerificationFailureApplicationService.class);
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String FAILED_STATUS = "FAILED";
    private static final Set<String> RECONCILIATION_ELIGIBLE_STATUSES = Set.of("SUCCEEDED", "STALE", "CONFLICT");
    /** SPEC-TW-024 domain-rules: "the third failure ... escalates" — this failure plus the prior count must not reach 3. */
    private static final int FAILURE_LIMIT = 3;

    private final TicketVerificationFailureGuardPort guardPort;
    private final TicketVerificationFailureRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketVerificationFailureAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyVerificationFailureApplicationService(
        TicketVerificationFailureGuardPort guardPort,
        TicketVerificationFailureRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketVerificationFailureAppliedEventMapper eventMapper,
        TicketTelemetry telemetry
    ) {
        this.guardPort = guardPort;
        this.repository = repository;
        this.historyWriter = historyWriter;
        this.auditRecordPort = auditRecordPort;
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
        this.eventMapper = eventMapper;
        this.telemetry = telemetry;
    }

    @Transactional
    @Override
    public ApplyVerificationFailureResult applyVerificationFailure(ApplyVerificationFailureCommand command) {
        var timer = telemetry.startApplyVerificationFailureTimer();
        try {
            Optional<TicketVerificationAttemptGuard> guardOpt = guardPort.loadGuard(command.verificationId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "no matching verification attempt", command);
                telemetry.recordApplyVerificationFailureOutcome("stale");
                return ApplyVerificationFailureResult.stale(command.ticketId(), command.verificationId());
            }
            TicketVerificationAttemptGuard guard = guardOpt.get();

            if (!guard.ticketId().equals(command.ticketId())) {
                log.warn(
                    "SECURITY_ALERT: verification.failed verificationId={} already belongs to a different ticket (eventId={}, ticketId={})",
                    command.verificationId(), command.eventId(), command.ticketId()
                );
                telemetry.recordApplyVerificationFailureOutcome("stale");
                return ApplyVerificationFailureResult.stale(command.ticketId(), command.verificationId());
            }

            if (FAILED_STATUS.equals(guard.attemptStatus())) {
                logOutcome("duplicate", "verification attempt already FAILED", command);
                telemetry.recordApplyVerificationFailureOutcome("duplicate");
                return ApplyVerificationFailureResult.duplicate(command.ticketId(), command.verificationId());
            }
            if (RECONCILIATION_ELIGIBLE_STATUSES.contains(guard.attemptStatus())) {
                return classifyConflict(guard, command);
            }
            if (!ACTIVE_STATUS.equals(guard.attemptStatus())) {
                logOutcome("stale", "verification attempt is not ACTIVE", command);
                telemetry.recordApplyVerificationFailureOutcome("stale");
                return ApplyVerificationFailureResult.stale(command.ticketId(), command.verificationId());
            }

            if (isStale(guard, command)) {
                logOutcome("stale", "ticket no longer VERIFYING or references do not match the current attempt/cycle", command);
                telemetry.recordApplyVerificationFailureOutcome("stale");
                return ApplyVerificationFailureResult.stale(command.ticketId(), command.verificationId());
            }

            int priorFailedCount = repository.countFailedAttempts(command.ticketId(), command.resolutionCycleId());
            boolean hasReachedFailureLimit = priorFailedCount + 1 >= FAILURE_LIMIT;

            Instant now = clock.now();
            TicketVerificationFailureApplied applied = Ticket.applyVerificationFailure(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(), command.verificationId(),
                command.workflowId(), command.resolutionCycleId(), command.attemptNumber(), command.failureCode(),
                command.failureClass(), command.unsafeResult(), hasReachedFailureLimit, command.failedAt(), command.eventId(), now
            );

            TicketVerificationFailureUpdateOutcome outcome = repository.applyVerificationFailure(new TicketVerificationFailureUpdate(
                command.ticketId(), guard.ticketVersion(), applied.newStatus(), command.verificationId(), command.failureCode(),
                command.failureClass(), command.unsafeResult(), command.failedAt(), command.eventId(), now
            ));
            if (outcome instanceof TicketVerificationFailureUpdateOutcome.Conflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyVerificationFailureOutcome("stale");
                return ApplyVerificationFailureResult.stale(command.ticketId(), command.verificationId());
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), applied.previousStatus(), applied.newStatus(),
                applied.transitionId(), applied.reasonCode(), "SERVICE", "verification-service",
                null, command.eventId(), command.workflowId(), applied.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "VERIFICATION_FAILURE_APPLIED", "ALLOWED",
                "SERVICE", "verification-service", "verification-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                applied.previousStatus().name(), applied.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(applied, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            ApplyVerificationFailureResult result = ApplyVerificationFailureResult.applied(applied);
            telemetry.recordApplyVerificationFailureOutcome(result.outcome().name().toLowerCase());
            return result;
        } finally {
            telemetry.stopApplyVerificationFailureTimer(timer);
        }
    }

    /**
     * {@code attemptStatus} is already terminal but not {@code FAILED} — a
     * {@code SUCCEEDED} (SPEC-TW-023), {@code STALE}, or already-flagged
     * {@code CONFLICT} outcome was already recorded for this attempt. Per
     * acceptance-criteria "Conflicting success enters reconciliation", this
     * late/racing failure can never overwrite that; it only flags the
     * attempt for reconciliation.
     */
    private ApplyVerificationFailureResult classifyConflict(TicketVerificationAttemptGuard guard, ApplyVerificationFailureCommand command) {
        boolean flagged = repository.markConflictRequiresReconciliation(command.ticketId(), command.verificationId(), command.eventId());
        if (!flagged) {
            logOutcome("stale", "existing attempt changed underneath the conflict flag update", command);
            telemetry.recordApplyVerificationFailureOutcome("stale");
            return ApplyVerificationFailureResult.stale(command.ticketId(), command.verificationId());
        }

        Instant now = clock.now();
        auditRecordPort.append(new AuditRecordEntry(
            UUID.randomUUID(), "BUSINESS_ACTION", "VERIFICATION_CONFLICT_RECONCILIATION_REQUIRED", "ALLOWED",
            "SERVICE", "verification-service", "verification-service",
            "TICKET", command.ticketId().toString(), guard.displayId().value(),
            guard.attemptStatus(), guard.attemptStatus(),
            currentTraceId(), command.eventId(), "SUCCESS", "INTERNAL", now, null, null
        ));

        logOutcome("conflict_requires_reconciliation", "a " + guard.attemptStatus() + " outcome was already recorded for this attempt", command);
        telemetry.recordApplyVerificationFailureOutcome("conflict_requires_reconciliation");
        return ApplyVerificationFailureResult.conflictRequiresReconciliation(command.ticketId(), command.verificationId());
    }

    private boolean isStale(TicketVerificationAttemptGuard guard, ApplyVerificationFailureCommand command) {
        if (guard.ticketStatus() != TicketStatus.VERIFYING) {
            return true;
        }
        if (!guard.attemptResolutionCycleId().equals(command.resolutionCycleId())) {
            return true;
        }
        if (!guard.currentResolutionCycleId().equals(command.resolutionCycleId())) {
            return true;
        }
        if (!guard.attemptWorkflowId().equals(command.workflowId())) {
            return true;
        }
        return guard.attemptNumber() != command.attemptNumber();
    }

    private void logOutcome(String outcome, String reason, ApplyVerificationFailureCommand command) {
        log.info(
            "verification.failed event processed as {}: {} (eventId={}, ticketId={}, verificationId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.verificationId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
