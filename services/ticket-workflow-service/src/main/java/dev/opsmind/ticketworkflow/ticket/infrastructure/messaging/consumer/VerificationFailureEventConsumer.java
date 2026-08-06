package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyVerificationFailureUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.VerificationFailureEventPayload;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.VerificationFailureEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SPEC-TW-024: consumes {@code verification.failed.v1} messages routed to
 * {@code ticket-workflow.verification-events.v1} (bound in {@code
 * RabbitMqConfiguration}, shared with SPEC-TW-023's {@code
 * verification.completed.v1}). This codebase splits the LLD's single
 * combined {@code verification.completed} contract (a {@code result} field
 * distinguishing {@code SUCCESS}/{@code FAILURE}) into two event types,
 * exactly mirroring the {@code tool.execution.completed}/{@code
 * tool.execution.failed} split from Phase 06 — SPEC-TW-023 owns {@code
 * verification.completed} ({@code result = SUCCESS} only, enforced by that
 * consumer's own payload schema), this consumer owns {@code
 * verification.failed}, matching this spec's own {@code asyncapi.yaml}
 * channel list. Invoked by {@link VerificationEventsDispatcher}, the
 * queue's sole {@code @RabbitListener}. Follows 06-event-contracts §13's
 * algorithm up through step 12 (parse → validate envelope schema →
 * validate event type/version → validate producer → validate payload
 * schema → map → apply use case); steps 13-17 (save/history/outbox/commit)
 * happen inside the use case's own {@code @Transactional} boundary.
 */
@Component
public class VerificationFailureEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(VerificationFailureEventConsumer.class);
    private static final String EVENT_TYPE = "verification.failed";
    private static final String EXPECTED_MAJOR_VERSION = "1";

    private final ConsumedEventValidator validator;
    private final EventProducerAllowlist producerAllowlist;
    private final VerificationFailureEventMapper mapper;
    private final ApplyVerificationFailureUseCase useCase;
    private final ObjectMapper objectMapper;
    private final TicketTelemetry telemetry;

    public VerificationFailureEventConsumer(
        ConsumedEventValidator validator,
        EventProducerAllowlist producerAllowlist,
        VerificationFailureEventMapper mapper,
        ApplyVerificationFailureUseCase useCase,
        ObjectMapper objectMapper,
        TicketTelemetry telemetry
    ) {
        this.validator = validator;
        this.producerAllowlist = producerAllowlist;
        this.mapper = mapper;
        this.useCase = useCase;
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
    }

    public void onMessage(String body) {
        Map<String, Object> envelopeMap = parseJson(body);
        validator.validateEnvelope(envelopeMap);
        ConsumedEventEnvelope envelope = objectMapper.convertValue(envelopeMap, ConsumedEventEnvelope.class);

        validateEventTypeAndVersion(envelope);
        validateProducer(envelope);

        validator.validatePayload(envelope.eventType(), envelope.eventVersion(), envelope.payload());
        VerificationFailureEventPayload payload = objectMapper.convertValue(envelope.payload(), VerificationFailureEventPayload.class);

        ApplyVerificationFailureCommand command = mapper.toCommand(envelope, payload);
        telemetry.recordVerificationFailedConsumed();
        useCase.applyVerificationFailure(command);
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            telemetry.recordVerificationFailedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(EVENT_TYPE, "malformed JSON body: " + e.getOriginalMessage());
        }
    }

    private void validateEventTypeAndVersion(ConsumedEventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            telemetry.recordVerificationFailedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unexpected eventType on the verification-events queue");
        }
        String majorVersion = envelope.eventVersion() == null ? "" : envelope.eventVersion().split("\\.")[0];
        if (!EXPECTED_MAJOR_VERSION.equals(majorVersion)) {
            telemetry.recordVerificationFailedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unsupported major version " + envelope.eventVersion());
        }
    }

    private void validateProducer(ConsumedEventEnvelope envelope) {
        if (!producerAllowlist.isAllowed(envelope.eventType(), envelope.producer())) {
            telemetry.recordVerificationFailedDlq("wrong_producer");
            log.warn("SECURITY_ALERT: rejected verification.failed from disallowed producer '{}' (eventId={})", envelope.producer(), envelope.eventId());
            throw new EventProducerNotAllowedException(envelope.eventType(), envelope.producer());
        }
    }
}
