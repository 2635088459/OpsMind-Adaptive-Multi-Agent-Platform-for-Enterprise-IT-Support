package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyVerificationSuccessUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.VerificationSuccessEventPayload;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.VerificationSuccessEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SPEC-TW-023: consumes {@code verification.completed.v1} messages (result
 * = {@code SUCCESS} — the consumed-payload schema's {@code const}
 * constraint on {@code result} rejects any other value straight to the
 * DLQ, since a {@code FAILURE} result is SPEC-TW-024's own event type in
 * this codebase's split of the LLD's single combined {@code
 * verification.completed} contract) routed to {@code
 * ticket-workflow.verification-events.v1} (bound in {@code
 * RabbitMqConfiguration}). Invoked by {@link
 * VerificationEventsDispatcher}, the queue's sole {@code @RabbitListener} —
 * see {@code ApprovalEventsDispatcher}'s Javadoc for why a per-event-type
 * listener on this queue does not work. Follows 06-event-contracts §13's
 * algorithm up through step 12 (parse → validate envelope schema →
 * validate event type/version → validate producer → validate payload
 * schema → map → apply use case); steps 13-17 (save/history/outbox/commit)
 * happen inside the use case's own {@code @Transactional} boundary.
 */
@Component
public class VerificationSuccessEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(VerificationSuccessEventConsumer.class);
    private static final String EVENT_TYPE = "verification.completed";
    private static final String EXPECTED_MAJOR_VERSION = "1";

    private final ConsumedEventValidator validator;
    private final EventProducerAllowlist producerAllowlist;
    private final VerificationSuccessEventMapper mapper;
    private final ApplyVerificationSuccessUseCase useCase;
    private final ObjectMapper objectMapper;
    private final TicketTelemetry telemetry;

    public VerificationSuccessEventConsumer(
        ConsumedEventValidator validator,
        EventProducerAllowlist producerAllowlist,
        VerificationSuccessEventMapper mapper,
        ApplyVerificationSuccessUseCase useCase,
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
        VerificationSuccessEventPayload payload = objectMapper.convertValue(envelope.payload(), VerificationSuccessEventPayload.class);

        ApplyVerificationSuccessCommand command = mapper.toCommand(envelope, payload);
        telemetry.recordVerificationCompletedConsumed();
        useCase.applyVerificationSuccess(command);
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            telemetry.recordVerificationCompletedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(EVENT_TYPE, "malformed JSON body: " + e.getOriginalMessage());
        }
    }

    private void validateEventTypeAndVersion(ConsumedEventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            telemetry.recordVerificationCompletedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unexpected eventType on the verification-events queue");
        }
        String majorVersion = envelope.eventVersion() == null ? "" : envelope.eventVersion().split("\\.")[0];
        if (!EXPECTED_MAJOR_VERSION.equals(majorVersion)) {
            telemetry.recordVerificationCompletedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unsupported major version " + envelope.eventVersion());
        }
    }

    private void validateProducer(ConsumedEventEnvelope envelope) {
        if (!producerAllowlist.isAllowed(envelope.eventType(), envelope.producer())) {
            telemetry.recordVerificationCompletedDlq("wrong_producer");
            log.warn("SECURITY_ALERT: rejected verification.completed from disallowed producer '{}' (eventId={})", envelope.producer(), envelope.eventId());
            throw new EventProducerNotAllowedException(envelope.eventType(), envelope.producer());
        }
    }
}
