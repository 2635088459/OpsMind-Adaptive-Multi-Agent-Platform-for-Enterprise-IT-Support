package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketUserInputResumedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UserReplyAndResumeUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyResumeUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyResumeUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketUserInputResumed;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageAuthor;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessage;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageAdded;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.UserInputRequestStatus;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC-TW-013 §1/domain-rules: a requester reply to
 * {@code POST .../user-input-requests/{requestId}/reply} always saves a
 * {@code PUBLIC_REQUESTER_MESSAGE}. It additionally resumes the ticket
 * ({@code WAITING_FOR_USER -> IN_PROGRESS}, transitionId {@code SM-015})
 * only when {@code requestId} is the ticket's current {@code OPEN} request
 * — domain-rules' "Old Requests" section and AC-04 both require a reply to
 * a stale request to still be saved, just without resuming
 * ({@code resumeApplied = false}, only the generic {@code
 * ticket.message.added} event, never the two SPEC-TW-013 events).
 */
@Service
public class UserReplyAndResumeApplicationService implements UserReplyAndResumeUseCase {

    private static final String OPERATION_ID = "userReplyAndResume";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/user-input-requests/{requestId}/reply";
    private static final String REQUIRED_SCOPE = "tickets:message:self";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;

    private final TicketUserReplyGuardPort guardPort;
    private final TicketMessageRepository messageRepository;
    private final TicketUserReplyRepository userReplyRepository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketMessageAddedEventMapper messageEventMapper;
    private final TicketUserInputResumedEventMapper resumeEventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public UserReplyAndResumeApplicationService(
        TicketUserReplyGuardPort guardPort,
        TicketMessageRepository messageRepository,
        TicketUserReplyRepository userReplyRepository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketMessageAddedEventMapper messageEventMapper,
        TicketUserInputResumedEventMapper resumeEventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.messageRepository = messageRepository;
        this.userReplyRepository = userReplyRepository;
        this.historyWriter = historyWriter;
        this.auditRecordPort = auditRecordPort;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
        this.requestHashCalculator = requestHashCalculator;
        this.messageEventMapper = messageEventMapper;
        this.resumeEventMapper = resumeEventMapper;
        this.telemetry = telemetry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Override
    public UserReplyAndResumeResult reply(UserReplyAndResumeCommand command) {
        var timer = telemetry.startUserReplyTimer();
        try {
            authorize(command.actor());
            Reservation reservation = reserveIdempotency(command);
            if (reservation.replayed() != null) {
                telemetry.recordUserReplyCommand("replay");
                return reservation.replayed();
            }
            Instant now = reservation.now();

            TicketUserReplyGuard guard = guardPort.loadGuard(command.ticketId(), command.requestId()).orElseThrow(TicketNotFoundException::new);
            checkOwnership(guard, command.actor());
            checkVersion(guard, command.expectedVersion());
            if (!guard.requestExistsForTicket()) {
                throw new TicketNotFoundException();
            }

            TicketMessageId messageId = TicketMessageId.of(UUID.randomUUID());
            MessageAuthor author = new MessageAuthor(command.actor().actorType(), command.actor().subject());
            TicketMessage message = TicketMessage.create(
                messageId, command.ticketId(), TicketMessageType.PUBLIC_REQUESTER_MESSAGE, author, command.body(), command.commandId(), now
            );
            messageRepository.save(message);

            String traceId = currentTraceId();
            boolean resumeEligible = guard.status() == TicketStatus.WAITING_FOR_USER && guard.requestStatus() == UserInputRequestStatus.OPEN;

            UserReplyAndResumeResult result;
            if (resumeEligible) {
                result = resumeTicket(command, guard, messageId, now, traceId);
            } else {
                result = saveAsPlainMessage(command, guard, message, messageId, now, traceId);
            }

            completeIdempotency(reservation.idempotencyRecordId(), command.ticketId(), result, now);
            telemetry.recordUserReplyCommand("success");
            telemetry.recordUserReplyResumeApplied(result.resumeApplied());
            return result;
        } finally {
            telemetry.stopUserReplyTimer(timer);
        }
    }

    private UserReplyAndResumeResult resumeTicket(
        UserReplyAndResumeCommand command, TicketUserReplyGuard guard, TicketMessageId messageId, Instant now, String traceId
    ) {
        TicketUserInputResumed resumed = Ticket.resumeFromUserReply(
            command.ticketId(), guard.status(), guard.version(), command.requestId(), messageId.value(),
            command.actor().actorType(), command.actor().subject(), now
        );

        applyResumeUpdate(resumed, command, now);

        historyWriter.append(new TicketStatusHistoryEntry(
            UUID.randomUUID(), command.ticketId(), resumed.previousStatus(), resumed.newStatus(),
            resumed.transitionId(), resumed.reasonCode(), command.actor().actorType(), command.actor().subject(),
            command.commandId(), null, null, resumed.aggregateVersion(), now
        ));

        auditRecordPort.append(new AuditRecordEntry(
            UUID.randomUUID(), "BUSINESS_ACTION", "USER_INPUT_RESUMED", "ALLOWED",
            command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
            "TICKET", command.ticketId().toString(), guard.displayId().value(),
            resumed.previousStatus().name(), resumed.newStatus().name(),
            traceId, command.commandId(), "SUCCESS", "INTERNAL", now, null, null
        ));

        outboxEventRepository.append(resumeEventMapper.mapReplyReceived(resumed, traceId, command.correlationId(), command.commandId()));
        outboxEventRepository.append(resumeEventMapper.mapResumed(resumed, traceId, command.correlationId(), command.commandId()));

        return new UserReplyAndResumeResult(
            command.ticketId(), resumed.requestId(), messageId, resumed.previousStatus(), resumed.newStatus(),
            resumed.repliedAt(), true, resumed.aggregateVersion(), false
        );
    }

    private UserReplyAndResumeResult saveAsPlainMessage(
        UserReplyAndResumeCommand command, TicketUserReplyGuard guard, TicketMessage message, TicketMessageId messageId, Instant now, String traceId
    ) {
        auditRecordPort.append(new AuditRecordEntry(
            UUID.randomUUID(), "BUSINESS_ACTION", "TICKET_MESSAGE_ADDED", "ALLOWED",
            command.actor().actorType(), command.actor().subject(), command.actor().clientId(),
            "TICKET_MESSAGE", messageId.toString(), guard.displayId().value(),
            null, null, traceId, command.commandId(), "SUCCESS", "SENSITIVE", now, null, null
        ));

        for (TicketMessageAdded event : message.pullDomainEvents()) {
            outboxEventRepository.append(messageEventMapper.map(event, traceId, command.correlationId(), command.commandId()));
        }

        return new UserReplyAndResumeResult(
            command.ticketId(), command.requestId(), messageId, guard.status(), guard.status(), now, false, guard.version(), false
        );
    }

    private void authorize(ActorContext actor) {
        if (!"EMPLOYEE".equals(actor.actorType()) || !actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordUserReplyAuthorizationDenied();
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private void checkOwnership(TicketUserReplyGuard guard, ActorContext actor) {
        if (!guard.requesterId().equals(actor.subject())) {
            throw new TicketNotFoundException();
        }
    }

    private void checkVersion(TicketUserReplyGuard guard, long expectedVersion) {
        if (guard.version() != expectedVersion) {
            telemetry.recordUserReplyConflict("version");
            throw new TicketVersionConflictException(guard.version());
        }
    }

    private void applyResumeUpdate(TicketUserInputResumed resumed, UserReplyAndResumeCommand command, Instant now) {
        TicketUserReplyResumeUpdateOutcome outcome = userReplyRepository.applyResume(new TicketUserReplyResumeUpdate(
            resumed.ticketId(), resumed.aggregateVersion() - 1, resumed.requestId(), resumed.messageId(), resumed.repliedAt(), now
        ));
        if (outcome instanceof TicketUserReplyResumeUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketUserReplyResumeUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordUserReplyConflict("version");
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketUserReplyResumeUpdateOutcome.InvalidState invalidState) {
            telemetry.recordUserReplyConflict("state");
            throw new InvalidStatusTransitionException(invalidState.currentStatus(), resumed.newStatus());
        }
        if (outcome instanceof TicketUserReplyResumeUpdateOutcome.RequestNotOpen) {
            telemetry.recordUserReplyConflict("request_not_open");
            throw new InvalidStatusTransitionException(resumed.previousStatus(), resumed.newStatus());
        }
    }

    private record Reservation(UUID idempotencyRecordId, Instant now, UserReplyAndResumeResult replayed) {
    }

    private Reservation reserveIdempotency(UserReplyAndResumeCommand command) {
        String actorScope = "user:" + command.actor().subject() + ":" + command.ticketId() + ":" + OPERATION_ID;
        String requestHash = requestHashCalculator.calculate("POST", ROUTE_TEMPLATE, actorScope, canonicalBody(command));

        Instant now = clock.now();
        UUID idempotencyRecordId = UUID.randomUUID();
        IdempotencyReservationOutcome outcome = idempotencyRepository.reserve(new IdempotencyReservationRequest(
            idempotencyRecordId, actorScope, command.idempotencyKey(), OPERATION_ID, requestHash, now, IDEMPOTENCY_TTL, STALE_THRESHOLD
        ));
        if (outcome instanceof IdempotencyReservationOutcome.Replayed replayed) {
            return new Reservation(idempotencyRecordId, now, deserializeResult(replayed.responseBodyJson()));
        }
        if (outcome instanceof IdempotencyReservationOutcome.KeyReused) {
            throw new IdempotencyKeyReusedException(command.idempotencyKey());
        }
        if (outcome instanceof IdempotencyReservationOutcome.RequestInProgress) {
            throw new RequestInProgressException(command.idempotencyKey());
        }
        IdempotencyReservationOutcome.Reserved reserved = (IdempotencyReservationOutcome.Reserved) outcome;
        return new Reservation(reserved.idempotencyRecordId(), now, null);
    }

    private Map<String, Object> canonicalBody(UserReplyAndResumeCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("requestId", command.requestId().toString());
        body.put("body", command.body().value());
        body.put("attachmentIds", command.attachmentIds());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private void completeIdempotency(UUID idempotencyRecordId, TicketId ticketId, UserReplyAndResumeResult result, Instant now) {
        idempotencyRepository.complete(idempotencyRecordId, new IdempotencyCompletion(
            "TICKET", ticketId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
        ));
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(UserReplyAndResumeResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("requestId", result.requestId().toString());
            body.put("messageId", result.messageId().value().toString());
            body.put("previousStatus", result.previousStatus().name());
            body.put("status", result.status().name());
            body.put("answeredAt", result.answeredAt().toString());
            body.put("resumeApplied", result.resumeApplied());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize user reply result", e);
        }
    }

    private UserReplyAndResumeResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new UserReplyAndResumeResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                UUID.fromString((String) body.get("requestId")),
                TicketMessageId.of(UUID.fromString((String) body.get("messageId"))),
                TicketStatus.valueOf((String) body.get("previousStatus")),
                TicketStatus.valueOf((String) body.get("status")),
                Instant.parse((String) body.get("answeredAt")),
                (Boolean) body.get("resumeApplied"),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
