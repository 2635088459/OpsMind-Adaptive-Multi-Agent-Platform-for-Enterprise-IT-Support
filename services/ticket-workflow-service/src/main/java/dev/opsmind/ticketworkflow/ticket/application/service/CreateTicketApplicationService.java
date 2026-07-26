package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketIntegrationEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.DisplayIdCollisionException;
import dev.opsmind.ticketworkflow.ticket.application.exception.DisplayIdGenerationExhaustedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CreateTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ResolvedSlaPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SlaPolicyResolver;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketDisplayIdGenerator;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketIdGenerator;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionCycleRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketSlaRepository;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketDomainEvent;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.model.TicketResolutionCycle;
import dev.opsmind.ticketworkflow.ticket.domain.model.TicketSlaCycle;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CreateTicketApplicationService implements CreateTicketUseCase {

    private static final String OPERATION_ID = "createTicket";
    private static final String REQUIRED_SCOPE = "tickets:create";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int MAX_DISPLAY_ID_ATTEMPTS = 3;
    private static final int SUCCESS_HTTP_STATUS = 201;

    private final TicketRepository ticketRepository;
    private final TicketResolutionCycleRepository resolutionCycleRepository;
    private final TicketSlaRepository slaRepository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TicketIdGenerator ticketIdGenerator;
    private final TicketDisplayIdGenerator displayIdGenerator;
    private final ClockPort clock;
    private final SlaPolicyResolver slaPolicyResolver;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketIntegrationEventMapper integrationEventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public CreateTicketApplicationService(
        TicketRepository ticketRepository,
        TicketResolutionCycleRepository resolutionCycleRepository,
        TicketSlaRepository slaRepository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        TicketIdGenerator ticketIdGenerator,
        TicketDisplayIdGenerator displayIdGenerator,
        ClockPort clock,
        SlaPolicyResolver slaPolicyResolver,
        RequestHashCalculator requestHashCalculator,
        TicketIntegrationEventMapper integrationEventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.ticketRepository = ticketRepository;
        this.resolutionCycleRepository = resolutionCycleRepository;
        this.slaRepository = slaRepository;
        this.historyWriter = historyWriter;
        this.auditRecordPort = auditRecordPort;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.ticketIdGenerator = ticketIdGenerator;
        this.displayIdGenerator = displayIdGenerator;
        this.clock = clock;
        this.slaPolicyResolver = slaPolicyResolver;
        this.requestHashCalculator = requestHashCalculator;
        this.integrationEventMapper = integrationEventMapper;
        this.telemetry = telemetry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Override
    public CreateTicketResult create(CreateTicketCommand command) {
        authorize(command.actor());

        String actorScope = actorScopeFor(command.actor());
        String requestHash = requestHashCalculator.calculate(
            "POST",
            "/api/v1/tickets",
            actorScope,
            canonicalBody(command)
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
            telemetry.recordIdempotencyReplay();
            return deserializeResult(replayed.responseBodyJson());
        }
        if (outcome instanceof IdempotencyReservationOutcome.KeyReused) {
            throw new IdempotencyKeyReusedException(command.idempotencyKey());
        }
        if (outcome instanceof IdempotencyReservationOutcome.RequestInProgress) {
            throw new RequestInProgressException(command.idempotencyKey());
        }

        IdempotencyReservationOutcome.Reserved reserved = (IdempotencyReservationOutcome.Reserved) outcome;

        TicketId ticketId = ticketIdGenerator.generate();
        RequesterId requesterId = RequesterId.of(command.actor().subject());
        UUID resolutionCycleId = UUID.randomUUID();
        UUID slaCycleId = UUID.randomUUID();

        Ticket ticket = createTicketWithDisplayIdRetry(command, ticketId, requesterId, resolutionCycleId, now);

        ResolvedSlaPolicy slaPolicy = slaPolicyResolver.resolve(command.applicationCode(), now);

        TicketResolutionCycle resolutionCycle = TicketResolutionCycle.openInitial(resolutionCycleId, ticketId, now);
        resolutionCycleRepository.save(resolutionCycle);

        TicketSlaCycle slaCycle = TicketSlaCycle.openInitial(
            slaCycleId,
            ticketId,
            resolutionCycleId,
            slaPolicy.policyId(),
            slaPolicy.responseDueAt(),
            slaPolicy.resolutionDueAt(),
            now
        );
        slaRepository.save(slaCycle);

        historyWriter.appendInitial(new TicketStatusHistoryEntry(
            UUID.randomUUID(),
            ticketId,
            null,
            TicketStatus.NEW,
            "SM-001",
            "TICKET_CREATED",
            command.actor().actorType(),
            command.actor().subject(),
            command.commandId(),
            null,
            null,
            0L,
            now
        ));

        String traceId = currentTraceId();

        auditRecordPort.append(new AuditRecordEntry(
            UUID.randomUUID(),
            "BUSINESS_ACTION",
            "TICKET_CREATED",
            "ALLOWED",
            command.actor().actorType(),
            command.actor().subject(),
            command.actor().clientId(),
            "TICKET",
            ticket.id().toString(),
            ticket.displayId().value(),
            null,
            "NEW",
            traceId,
            command.commandId(),
            "SUCCESS",
            "SENSITIVE",
            now,
            null,
            null
        ));

        for (TicketDomainEvent event : ticket.pullDomainEvents()) {
            if (event instanceof TicketCreated created) {
                OutboxEventEntry outboxEntry = integrationEventMapper.mapTicketCreated(
                    created,
                    traceId,
                    command.correlationId(),
                    command.commandId()
                );
                outboxEventRepository.append(outboxEntry);
            }
        }

        CreateTicketResult result = new CreateTicketResult(
            ticket.id(),
            ticket.displayId(),
            ticket.status(),
            ticket.createdAt(),
            ticket.version(),
            false
        );

        idempotencyRepository.complete(reserved.idempotencyRecordId(), new IdempotencyCompletion(
            "TICKET",
            ticket.id().toString(),
            SUCCESS_HTTP_STATUS,
            serializeResult(result),
            now
        ));

        telemetry.recordCreated(command.applicationCode(), command.source());

        return result;
    }

    private void authorize(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordAuthorizationDenied(OPERATION_ID);
            throw new TicketAuthorizationException(REQUIRED_SCOPE);
        }
    }

    private String actorScopeFor(ActorContext actor) {
        return "user:" + actor.subject() + ":" + OPERATION_ID;
    }

    private Map<String, Object> canonicalBody(CreateTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", command.title().value());
        body.put("description", command.description().value());
        body.put("applicationCode", command.applicationCode().name());
        body.put("source", command.source().name());
        return body;
    }

    private Ticket createTicketWithDisplayIdRetry(
        CreateTicketCommand command,
        TicketId ticketId,
        RequesterId requesterId,
        UUID resolutionCycleId,
        Instant now
    ) {
        DisplayIdCollisionException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_DISPLAY_ID_ATTEMPTS; attempt++) {
            TicketDisplayId displayId = displayIdGenerator.generate();
            Ticket candidate = Ticket.create(
                ticketId,
                displayId,
                requesterId,
                command.title(),
                command.description(),
                command.applicationCode(),
                command.source(),
                resolutionCycleId,
                now
            );
            try {
                ticketRepository.save(candidate);
                return candidate;
            } catch (DisplayIdCollisionException e) {
                lastFailure = e;
            }
        }
        throw new DisplayIdGenerationExhaustedException(MAX_DISPLAY_ID_ATTEMPTS);
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(CreateTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("displayId", result.displayId().value());
            body.put("status", result.status().name());
            body.put("createdAt", result.createdAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize create ticket result", e);
        }
    }

    private CreateTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new CreateTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketDisplayId.of((String) body.get("displayId")),
                TicketStatus.valueOf((String) body.get("status")),
                Instant.parse((String) body.get("createdAt")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
