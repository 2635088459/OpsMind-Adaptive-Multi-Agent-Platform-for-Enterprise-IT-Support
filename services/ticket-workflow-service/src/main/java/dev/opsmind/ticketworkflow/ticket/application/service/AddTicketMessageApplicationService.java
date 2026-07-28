package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketMessageAddedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketMessageNotAllowedInStateException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AddTicketMessageUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageWriteGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketWriteGuard;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageAuthor;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessage;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageAdded;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageId;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Add Ticket Message (SPEC-TW-004). Mirrors CreateTicketApplicationService's
 * shape: coarse scope check, then a single {@code @Transactional} method
 * covering idempotency reservation through completion, so any failure
 * (resource-not-found, terminal-state rejection) rolls back the whole
 * attempt — including the idempotency reservation itself — leaving a clean
 * slate for retry (BI-095).
 */
@Service
public class AddTicketMessageApplicationService implements AddTicketMessageUseCase {

    private static final String OPERATION_ID = "addTicketMessage";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/messages";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 201;
    private static final Set<TicketStatus> TERMINAL_STATUSES = Set.of(TicketStatus.CLOSED, TicketStatus.CANCELLED);

    private final TicketMessageRepository messageRepository;
    private final TicketMessageWriteGuardPort writeGuardPort;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketMessageAddedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public AddTicketMessageApplicationService(
        TicketMessageRepository messageRepository,
        TicketMessageWriteGuardPort writeGuardPort,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketMessageAddedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.messageRepository = messageRepository;
        this.writeGuardPort = writeGuardPort;
        this.auditRecordPort = auditRecordPort;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
        this.requestHashCalculator = requestHashCalculator;
        this.eventMapper = eventMapper;
        this.telemetry = telemetry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Override
    public AddTicketMessageResult addMessage(AddTicketMessageCommand command) {
        var timerSample = telemetry.startMessageAddTimer();
        try {
            String requiredScope = requiredScope(command.messageType());
            if (!command.actor().hasScope(requiredScope)) {
                telemetry.recordMessageAuthorizationDenied();
                throw new TicketAuthorizationException(requiredScope);
            }

            String actorScope = actorScopeFor(command);
            String requestHash = requestHashCalculator.calculate(
                "POST", ROUTE_TEMPLATE, actorScope, canonicalBody(command)
            );

            Instant now = clock.now();
            UUID idempotencyRecordId = UUID.randomUUID();

            IdempotencyReservationOutcome outcome = idempotencyRepository.reserve(new IdempotencyReservationRequest(
                idempotencyRecordId,
                actorScope,
                command.idempotencyKey(),
                OPERATION_ID,
                requestHash,
                now,
                IDEMPOTENCY_TTL,
                STALE_THRESHOLD
            ));

            if (outcome instanceof IdempotencyReservationOutcome.Replayed replayed) {
                telemetry.recordMessageReplay();
                return deserializeResult(replayed.responseBodyJson());
            }
            if (outcome instanceof IdempotencyReservationOutcome.KeyReused) {
                throw new IdempotencyKeyReusedException(command.idempotencyKey());
            }
            if (outcome instanceof IdempotencyReservationOutcome.RequestInProgress) {
                throw new RequestInProgressException(command.idempotencyKey());
            }
            IdempotencyReservationOutcome.Reserved reserved = (IdempotencyReservationOutcome.Reserved) outcome;

            TicketWriteGuard guard = writeGuardPort.loadGuard(command.ticketId())
                .orElseThrow(TicketNotFoundException::new);

            authorizeResource(command, guard);

            if (TERMINAL_STATUSES.contains(guard.status())) {
                telemetry.recordMessageStateRejected();
                throw new TicketMessageNotAllowedInStateException();
            }

            TicketMessageId messageId = TicketMessageId.of(UUID.randomUUID());
            MessageAuthor author = new MessageAuthor(command.actor().actorType(), command.actor().subject());
            TicketMessage message = TicketMessage.create(
                messageId, command.ticketId(), command.messageType(), author, command.content(), command.commandId(), now
            );

            messageRepository.save(message);

            String traceId = currentTraceId();

            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(),
                "BUSINESS_ACTION",
                message.messageType() == TicketMessageType.INTERNAL_SUPPORT_NOTE ? "TICKET_INTERNAL_NOTE_ADDED" : "TICKET_MESSAGE_ADDED",
                "ALLOWED",
                command.actor().actorType(),
                command.actor().subject(),
                command.actor().clientId(),
                "TICKET_MESSAGE",
                messageId.toString(),
                null,
                null,
                null,
                traceId,
                command.commandId(),
                "SUCCESS",
                "SENSITIVE",
                now,
                null,
                null
            ));

            for (TicketMessageAdded event : message.pullDomainEvents()) {
                outboxEventRepository.append(eventMapper.map(event, traceId, command.correlationId(), command.commandId()));
            }

            AddTicketMessageResult result = new AddTicketMessageResult(
                messageId,
                command.ticketId(),
                message.messageType(),
                message.visibility(),
                command.actor().actorType(),
                command.content().value(),
                now,
                message.version(),
                false
            );

            idempotencyRepository.complete(reserved.idempotencyRecordId(), new IdempotencyCompletion(
                "TICKET_MESSAGE", messageId.toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
            ));

            telemetry.recordMessageAdd(command.actor().actorType(), message.messageType(), message.visibility());

            return result;
        } finally {
            telemetry.stopMessageAddTimer(timerSample);
        }
    }

    private void authorizeResource(AddTicketMessageCommand command, TicketWriteGuard guard) {
        boolean authorized = "EMPLOYEE".equals(command.actor().actorType())
            ? guard.requesterId().equals(command.actor().subject())
            : command.allowedApplicationCodes().contains(guard.applicationCode());
        if (!authorized) {
            throw new TicketNotFoundException();
        }
    }

    private String requiredScope(TicketMessageType messageType) {
        return switch (messageType) {
            case PUBLIC_REQUESTER_MESSAGE -> "tickets:message:self";
            case PUBLIC_SUPPORT_MESSAGE -> "tickets:message:public";
            case INTERNAL_SUPPORT_NOTE -> "tickets:message:internal";
        };
    }

    private String actorScopeFor(AddTicketMessageCommand command) {
        return "user:" + command.actor().subject() + ":" + command.ticketId() + ":" + OPERATION_ID;
    }

    private Map<String, Object> canonicalBody(AddTicketMessageCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("messageType", command.messageType().name());
        body.put("content", command.content().value());
        return body;
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(AddTicketMessageResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messageId", result.messageId().value().toString());
            body.put("ticketId", result.ticketId().value().toString());
            body.put("messageType", result.messageType().name());
            body.put("visibility", result.visibility().name());
            body.put("authorType", result.authorType());
            body.put("content", result.content());
            body.put("createdAt", result.createdAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize add message result", e);
        }
    }

    private AddTicketMessageResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new AddTicketMessageResult(
                TicketMessageId.of(UUID.fromString((String) body.get("messageId"))),
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketMessageType.valueOf((String) body.get("messageType")),
                MessageVisibility.valueOf((String) body.get("visibility")),
                (String) body.get("authorType"),
                (String) body.get("content"),
                Instant.parse((String) body.get("createdAt")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
