package dev.opsmind.ticketworkflow.ticket.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketTriagedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageCategoryInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageSubcategoryInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.TriageTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogCategory;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogSubcategory;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogSupportQueue;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueCatalogPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCategoryCatalogPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketSubcategoryCatalogPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageGuard;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketTriaged;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Triage Ticket (SPEC-TW-007). Mirrors {@code AddTicketMessageApplicationService}'s
 * shape (coarse authorization, then a single {@code @Transactional} method
 * spanning idempotency reservation through completion) extended with the
 * optimistic-locking and catalog-validation steps this codebase's first
 * true status-mutating write command needs (SPEC-TW-007 §10):
 * <ol>
 *   <li>coarse role + scope check;</li>
 *   <li>idempotency reservation;</li>
 *   <li>load the write guard (404), then check version (412) and state (409);</li>
 *   <li>validate category/subcategory/queue (422) and queue-team grant (403);</li>
 *   <li>apply the domain rule, persist via the version/state-guarded {@code UPDATE},
 *       append history/audit/outbox, complete idempotency.</li>
 * </ol>
 */
@Service
public class TriageTicketApplicationService implements TriageTicketUseCase {

    private static final String OPERATION_ID = "triageTicket";
    private static final String ROUTE_TEMPLATE = "/api/v1/tickets/{ticketId}/triage";
    private static final String REQUIRED_SCOPE = "ticket:triage";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final int SUCCESS_HTTP_STATUS = 200;
    private static final String TRANSITION_ID = "SM-002";
    private static final String REASON_CODE = "TICKET_TRIAGED";

    private final TicketTriageGuardPort guardPort;
    private final TicketCategoryCatalogPort categoryCatalogPort;
    private final TicketSubcategoryCatalogPort subcategoryCatalogPort;
    private final SupportQueueCatalogPort supportQueueCatalogPort;
    private final TicketTriageRepository triageRepository;
    private final TicketHistoryWriter historyWriter;
    private final AuditRecordPort auditRecordPort;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ClockPort clock;
    private final RequestHashCalculator requestHashCalculator;
    private final TicketTriagedEventMapper eventMapper;
    private final TicketTelemetry telemetry;
    private final ObjectMapper objectMapper;

    public TriageTicketApplicationService(
        TicketTriageGuardPort guardPort,
        TicketCategoryCatalogPort categoryCatalogPort,
        TicketSubcategoryCatalogPort subcategoryCatalogPort,
        SupportQueueCatalogPort supportQueueCatalogPort,
        TicketTriageRepository triageRepository,
        TicketHistoryWriter historyWriter,
        AuditRecordPort auditRecordPort,
        OutboxEventRepository outboxEventRepository,
        IdempotencyRepository idempotencyRepository,
        ClockPort clock,
        RequestHashCalculator requestHashCalculator,
        TicketTriagedEventMapper eventMapper,
        TicketTelemetry telemetry,
        ObjectMapper objectMapper
    ) {
        this.guardPort = guardPort;
        this.categoryCatalogPort = categoryCatalogPort;
        this.subcategoryCatalogPort = subcategoryCatalogPort;
        this.supportQueueCatalogPort = supportQueueCatalogPort;
        this.triageRepository = triageRepository;
        this.historyWriter = historyWriter;
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
    public TriageTicketResult triage(TriageTicketCommand command) {
        var timerSample = telemetry.startTriageTimer();
        try {
            authorizeActorType(command.actor());
            authorizeScope(command.actor());

            String actorScope = actorScopeFor(command);
            String requestHash = requestHashCalculator.calculate("POST", ROUTE_TEMPLATE, actorScope, canonicalBody(command));

            Instant now = clock.now();
            UUID idempotencyRecordId = UUID.randomUUID();

            IdempotencyReservationOutcome outcome = idempotencyRepository.reserve(new IdempotencyReservationRequest(
                idempotencyRecordId, actorScope, command.idempotencyKey(), OPERATION_ID, requestHash, now, IDEMPOTENCY_TTL, STALE_THRESHOLD
            ));

            if (outcome instanceof IdempotencyReservationOutcome.Replayed replayed) {
                telemetry.recordTriageReplay();
                return deserializeResult(replayed.responseBodyJson());
            }
            if (outcome instanceof IdempotencyReservationOutcome.KeyReused) {
                throw new IdempotencyKeyReusedException(command.idempotencyKey());
            }
            if (outcome instanceof IdempotencyReservationOutcome.RequestInProgress) {
                throw new RequestInProgressException(command.idempotencyKey());
            }
            IdempotencyReservationOutcome.Reserved reserved = (IdempotencyReservationOutcome.Reserved) outcome;

            TicketTriageGuard guard = guardPort.loadGuard(command.ticketId()).orElseThrow(TicketNotFoundException::new);

            if (guard.version() != command.expectedVersion()) {
                telemetry.recordTriageVersionConflict();
                throw new TicketVersionConflictException(guard.version());
            }
            if (guard.status() != TicketStatus.NEW) {
                telemetry.recordTriageStateRejected();
                throw new InvalidTicketTransitionException(guard.status(), TicketStatus.NEW);
            }

            CatalogCategory category = categoryCatalogPort.findActiveById(command.categoryId())
                .orElseThrow(() -> failCatalog("category", new TriageCategoryInvalidException()));

            if (command.subcategoryId() != null) {
                CatalogSubcategory subcategory = subcategoryCatalogPort.findActiveById(command.subcategoryId())
                    .orElseThrow(() -> failCatalog("subcategory", new TriageSubcategoryInvalidException()));
                if (!subcategory.categoryId().equals(category.categoryId())) {
                    throw failCatalog("subcategory", new TriageSubcategoryInvalidException());
                }
            }

            CatalogSupportQueue queue = supportQueueCatalogPort.findActiveById(command.supportQueueId())
                .orElseThrow(() -> failCatalog("queue", new SupportQueueInvalidException()));

            if (!command.allowedTeamIds().contains(queue.teamId())) {
                telemetry.recordTriageAuthorizationDenied("queue_scope");
                throw new QueueAccessDeniedException();
            }

            TicketTriaged triaged = Ticket.triage(
                command.ticketId(),
                guard.status(),
                guard.version(),
                command.categoryId(),
                command.subcategoryId(),
                command.priority(),
                command.supportQueueId(),
                command.actor().actorType(),
                command.actor().subject(),
                now
            );

            applyUpdate(command, queue, now);

            historyWriter.append(new TicketStatusHistoryEntry(
                UUID.randomUUID(),
                command.ticketId(),
                triaged.fromStatus(),
                triaged.toStatus(),
                TRANSITION_ID,
                REASON_CODE,
                command.actor().actorType(),
                command.actor().subject(),
                command.commandId(),
                null,
                null,
                triaged.aggregateVersion(),
                now
            ));

            String traceId = currentTraceId();

            auditRecordPort.append(new AuditRecordEntry(
                UUID.randomUUID(),
                "BUSINESS_ACTION",
                "TICKET_TRIAGED",
                "ALLOWED",
                command.actor().actorType(),
                command.actor().subject(),
                command.actor().clientId(),
                "TICKET",
                command.ticketId().toString(),
                guard.displayId().value(),
                triaged.fromStatus().name(),
                triaged.toStatus().name(),
                traceId,
                command.commandId(),
                "SUCCESS",
                "INTERNAL",
                now,
                null,
                null
            ));

            outboxEventRepository.append(eventMapper.map(triaged, traceId, command.correlationId(), command.commandId()));

            TriageTicketResult result = new TriageTicketResult(
                command.ticketId(),
                triaged.toStatus(),
                triaged.categoryId(),
                triaged.subcategoryId(),
                triaged.priority(),
                triaged.supportQueueId(),
                command.actor().subject(),
                now,
                triaged.aggregateVersion(),
                false
            );

            idempotencyRepository.complete(reserved.idempotencyRecordId(), new IdempotencyCompletion(
                "TICKET", command.ticketId().toString(), SUCCESS_HTTP_STATUS, serializeResult(result), now
            ));

            telemetry.recordTriaged(command.priority());

            return result;
        } finally {
            telemetry.stopTriageTimer(timerSample);
        }
    }

    private void applyUpdate(TriageTicketCommand command, CatalogSupportQueue queue, Instant now) {
        TicketTriageUpdateOutcome outcome = triageRepository.applyTriage(new TicketTriageUpdate(
            command.ticketId(),
            command.expectedVersion(),
            command.categoryId(),
            command.subcategoryId(),
            command.priority(),
            command.supportQueueId(),
            queue.teamId(),
            command.actor().subject(),
            now,
            now
        ));

        if (outcome instanceof TicketTriageUpdateOutcome.TicketMissing) {
            throw new TicketNotFoundException();
        }
        if (outcome instanceof TicketTriageUpdateOutcome.VersionMismatch mismatch) {
            telemetry.recordTriageVersionConflict();
            throw new TicketVersionConflictException(mismatch.currentVersion());
        }
        if (outcome instanceof TicketTriageUpdateOutcome.InvalidState invalidState) {
            telemetry.recordTriageStateRejected();
            throw new InvalidTicketTransitionException(invalidState.currentStatus(), TicketStatus.NEW);
        }
    }

    private <E extends RuntimeException> E failCatalog(String catalogType, E exception) {
        telemetry.recordTriageCatalogInvalid(catalogType);
        return exception;
    }

    private void authorizeActorType(ActorContext actor) {
        if ("EMPLOYEE".equals(actor.actorType())) {
            telemetry.recordTriageAuthorizationDenied("role");
            throw new TriageNotAllowedException();
        }
    }

    private void authorizeScope(ActorContext actor) {
        if (!actor.hasScope(REQUIRED_SCOPE)) {
            telemetry.recordTriageAuthorizationDenied("scope");
            throw new QueueAccessDeniedException();
        }
    }

    private String actorScopeFor(TriageTicketCommand command) {
        return "user:" + command.actor().subject() + ":" + command.ticketId() + ":" + OPERATION_ID;
    }

    private Map<String, Object> canonicalBody(TriageTicketCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", command.ticketId().toString());
        body.put("categoryId", command.categoryId().toString());
        body.put("subcategoryId", command.subcategoryId() == null ? null : command.subcategoryId().toString());
        body.put("priority", command.priority().name());
        body.put("supportQueueId", command.supportQueueId().toString());
        body.put("reason", command.reason());
        body.put("expectedVersion", command.expectedVersion());
        return body;
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String serializeResult(TriageTicketResult result) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", result.ticketId().value().toString());
            body.put("status", result.status().name());
            body.put("categoryId", result.categoryId().toString());
            body.put("subcategoryId", result.subcategoryId() == null ? null : result.subcategoryId().toString());
            body.put("priority", result.priority().name());
            body.put("supportQueueId", result.supportQueueId().toString());
            body.put("triagedBy", result.triagedBy());
            body.put("triagedAt", result.triagedAt().toString());
            body.put("version", result.version());
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize triage ticket result", e);
        }
    }

    private TriageTicketResult deserializeResult(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(json, Map.class);
            return new TriageTicketResult(
                TicketId.of(UUID.fromString((String) body.get("ticketId"))),
                TicketStatus.valueOf((String) body.get("status")),
                TicketCategoryId.of(UUID.fromString((String) body.get("categoryId"))),
                body.get("subcategoryId") == null ? null : TicketSubcategoryId.of(UUID.fromString((String) body.get("subcategoryId"))),
                TicketPriority.valueOf((String) body.get("priority")),
                SupportQueueId.of(UUID.fromString((String) body.get("supportQueueId"))),
                (String) body.get("triagedBy"),
                Instant.parse((String) body.get("triagedAt")),
                ((Number) body.get("version")).longValue(),
                true
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotency response", e);
        }
    }
}
