package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketVerificationSuccessAppliedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyVerificationSuccessUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationAttemptGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketVerificationSuccessUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketVerificationSuccessApplied;
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
 * SPEC-TW-023 §1/domain-rules §1: {@code VERIFYING -> VERIFYING} (transitionId
 * {@code SM-026}). Mirrors {@code ApplyToolResultUnknownApplicationService}'s
 * (SPEC-TW-021) three-way existing-state classification shape, transplanted
 * from {@code ticket_tool_execution_results.result_status} to {@code
 * ticket_verification_attempts.attempt_status}: no attempt found for
 * {@code verificationId} is {@code STALE} (unknown/bogus reference); an
 * already-{@code SUCCEEDED} attempt is {@code DUPLICATE} ("duplicate is
 * idempotently ACKed"); an attempt already in a different terminal state
 * ({@code FAILED}/{@code STALE}/{@code CONFLICT}) is {@code
 * CONFLICT_REQUIRES_RECONCILIATION} — this event never silently overwrites
 * a conflicting result. Only an {@code ACTIVE} attempt proceeds to the
 * full reference match (workflow, resolution cycle — both the attempt's
 * own and the ticket's *current* one, since a reopen since the attempt
 * started would mean this event is now stale — and attempt number).
 */
@Service
public class ApplyVerificationSuccessApplicationService implements ApplyVerificationSuccessUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyVerificationSuccessApplicationService.class);
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String SUCCEEDED_STATUS = "SUCCEEDED";
    private static final Set<String> RECONCILIATION_ELIGIBLE_STATUSES = Set.of("FAILED", "STALE", "CONFLICT");

    private final TicketVerificationSuccessGuardPort guardPort;
    private final TicketVerificationSuccessRepository repository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final ClockPort clock;
    private final TicketVerificationSuccessAppliedEventMapper eventMapper;
    private final TicketTelemetry telemetry;

    public ApplyVerificationSuccessApplicationService(
        TicketVerificationSuccessGuardPort guardPort,
        TicketVerificationSuccessRepository repository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        ClockPort clock,
        TicketVerificationSuccessAppliedEventMapper eventMapper,
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
    public ApplyVerificationSuccessResult applyVerificationSuccess(ApplyVerificationSuccessCommand command) {
        var timer = telemetry.startApplyVerificationSuccessTimer();
        try {
            Optional<TicketVerificationAttemptGuard> guardOpt = guardPort.loadGuard(command.verificationId());
            if (guardOpt.isEmpty()) {
                logOutcome("stale", "no matching verification attempt", command);
                telemetry.recordApplyVerificationSuccessOutcome("stale");
                return ApplyVerificationSuccessResult.stale(command.ticketId(), command.verificationId());
            }
            TicketVerificationAttemptGuard guard = guardOpt.get();

            if (!guard.ticketId().equals(command.ticketId())) {
                log.warn(
                    "SECURITY_ALERT: verification.completed verificationId={} already belongs to a different ticket (eventId={}, ticketId={})",
                    command.verificationId(), command.eventId(), command.ticketId()
                );
                telemetry.recordApplyVerificationSuccessOutcome("stale");
                return ApplyVerificationSuccessResult.stale(command.ticketId(), command.verificationId());
            }

            if (SUCCEEDED_STATUS.equals(guard.attemptStatus())) {
                logOutcome("duplicate", "verification attempt already SUCCEEDED", command);
                telemetry.recordApplyVerificationSuccessOutcome("duplicate");
                return ApplyVerificationSuccessResult.duplicate(command.ticketId(), command.verificationId());
            }
            if (RECONCILIATION_ELIGIBLE_STATUSES.contains(guard.attemptStatus())) {
                return classifyConflict(guard, command);
            }
            if (!ACTIVE_STATUS.equals(guard.attemptStatus())) {
                logOutcome("stale", "verification attempt is not ACTIVE", command);
                telemetry.recordApplyVerificationSuccessOutcome("stale");
                return ApplyVerificationSuccessResult.stale(command.ticketId(), command.verificationId());
            }

            if (isStale(guard, command)) {
                logOutcome("stale", "ticket no longer VERIFYING or references do not match the current attempt/cycle", command);
                telemetry.recordApplyVerificationSuccessOutcome("stale");
                return ApplyVerificationSuccessResult.stale(command.ticketId(), command.verificationId());
            }

            Instant now = clock.now();
            TicketVerificationSuccessApplied applied = Ticket.applyVerificationSuccess(
                command.ticketId(), guard.ticketStatus(), guard.assigneeId(), guard.ticketVersion(), command.verificationId(),
                command.workflowId(), command.resolutionCycleId(), command.attemptNumber(), command.verificationEvidenceId(),
                command.evidenceSummary(), command.completedAt(), command.eventId(), now
            );

            TicketVerificationSuccessUpdateOutcome outcome = repository.applyVerificationSuccess(new TicketVerificationSuccessUpdate(
                command.ticketId(), guard.ticketVersion(), command.verificationId(), command.verificationEvidenceId(),
                command.evidenceSummary(), command.completedAt(), command.eventId(), now
            ));
            if (outcome instanceof TicketVerificationSuccessUpdateOutcome.Conflict) {
                logOutcome("stale", "concurrent modification detected at write time", command);
                telemetry.recordApplyVerificationSuccessOutcome("stale");
                return ApplyVerificationSuccessResult.stale(command.ticketId(), command.verificationId());
            }

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(), command.ticketId(), applied.previousStatus(), applied.newStatus(),
                applied.transitionId(), applied.reasonCode(), "SERVICE", "verification-service",
                null, command.eventId(), command.workflowId(), applied.aggregateVersion(), now
            ));

            String traceId = currentTraceId();
            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(), "BUSINESS_ACTION", "VERIFICATION_SUCCESS_APPLIED", "ALLOWED",
                "SERVICE", "verification-service", "verification-service",
                "TICKET", command.ticketId().toString(), guard.displayId().value(),
                applied.previousStatus().name(), applied.newStatus().name(),
                traceId, command.eventId(), "SUCCESS", "INTERNAL", now, null, null
            ));
            outboxEventRepository.append(eventMapper.map(applied, guard.supportQueueId(), traceId, command.correlationId(), command.eventId()));

            telemetry.recordApplyVerificationSuccessOutcome("applied");
            return ApplyVerificationSuccessResult.applied(applied);
        } finally {
            telemetry.stopApplyVerificationSuccessTimer(timer);
        }
    }

    /**
     * {@code attemptStatus} is already terminal but not {@code SUCCEEDED} —
     * a {@code FAILED} (SPEC-TW-024), {@code STALE}, or already-flagged
     * {@code CONFLICT} outcome was already recorded for this attempt.
     * Per domain-rules "successful verification is the terminal state for
     * the current active attempt", this late/racing success can never
     * overwrite that; it only flags the attempt for reconciliation.
     */
    private ApplyVerificationSuccessResult classifyConflict(TicketVerificationAttemptGuard guard, ApplyVerificationSuccessCommand command) {
        boolean flagged = repository.markConflictRequiresReconciliation(command.ticketId(), command.verificationId(), command.eventId());
        if (!flagged) {
            logOutcome("stale", "existing attempt changed underneath the conflict flag update", command);
            telemetry.recordApplyVerificationSuccessOutcome("stale");
            return ApplyVerificationSuccessResult.stale(command.ticketId(), command.verificationId());
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
        telemetry.recordApplyVerificationSuccessOutcome("conflict_requires_reconciliation");
        return ApplyVerificationSuccessResult.conflictRequiresReconciliation(command.ticketId(), command.verificationId());
    }

    private boolean isStale(TicketVerificationAttemptGuard guard, ApplyVerificationSuccessCommand command) {
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

    private void logOutcome(String outcome, String reason, ApplyVerificationSuccessCommand command) {
        log.info(
            "verification.completed event processed as {}: {} (eventId={}, ticketId={}, verificationId={}, workflowId={})",
            outcome, reason, command.eventId(), command.ticketId(), command.verificationId(), command.workflowId()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
