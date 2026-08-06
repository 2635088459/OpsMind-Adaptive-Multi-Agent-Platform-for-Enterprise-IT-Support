package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolResultUnknownCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyToolResultUnknownUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ToolResultUnknownEventPayload;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.ToolResultUnknownEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SPEC-TW-021: consumes {@code tool.execution.result_unknown.v1} messages
 * routed to {@code ticket-workflow.tool-execution-events.v1} (bound in
 * {@code RabbitMqConfiguration} under the routing key {@code
 * tool.execution.result-unknown.v1} — hyphenated, since RabbitMQ routing
 * keys allow it, unlike this envelope's own {@code eventType} field, which
 * must satisfy the shared envelope schema's {@code
 * ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$} pattern and therefore uses an
 * underscore — see {@code ApprovalEventsDispatcher}'s Javadoc for the same
 * situation with {@code policy.action-auto-approved.v1}). Invoked by {@link
 * ToolExecutionEventsDispatcher}, the queue's sole {@code @RabbitListener}.
 * Follows 06-event-contracts §13's algorithm up through step 12 (parse →
 * validate envelope schema → validate event type/version → validate
 * producer → validate payload schema → map → apply use case); steps 13-17
 * (save/history/outbox/commit) happen inside the use case's own {@code
 * @Transactional} boundary.
 */
@Component
public class ToolResultUnknownEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultUnknownEventConsumer.class);
    private static final String EVENT_TYPE = "tool.execution.result_unknown";
    private static final String EXPECTED_MAJOR_VERSION = "1";

    private final ConsumedEventValidator validator;
    private final EventProducerAllowlist producerAllowlist;
    private final ToolResultUnknownEventMapper mapper;
    private final ApplyToolResultUnknownUseCase useCase;
    private final ObjectMapper objectMapper;
    private final TicketTelemetry telemetry;

    public ToolResultUnknownEventConsumer(
        ConsumedEventValidator validator,
        EventProducerAllowlist producerAllowlist,
        ToolResultUnknownEventMapper mapper,
        ApplyToolResultUnknownUseCase useCase,
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
        ToolResultUnknownEventPayload payload = objectMapper.convertValue(envelope.payload(), ToolResultUnknownEventPayload.class);

        ApplyToolResultUnknownCommand command = mapper.toCommand(envelope, payload);
        telemetry.recordToolResultUnknownConsumed();
        useCase.applyToolResultUnknown(command);
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            telemetry.recordToolResultUnknownDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(EVENT_TYPE, "malformed JSON body: " + e.getOriginalMessage());
        }
    }

    private void validateEventTypeAndVersion(ConsumedEventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            telemetry.recordToolResultUnknownDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unexpected eventType on the tool-execution-events queue");
        }
        String majorVersion = envelope.eventVersion() == null ? "" : envelope.eventVersion().split("\\.")[0];
        if (!EXPECTED_MAJOR_VERSION.equals(majorVersion)) {
            telemetry.recordToolResultUnknownDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unsupported major version " + envelope.eventVersion());
        }
    }

    private void validateProducer(ConsumedEventEnvelope envelope) {
        if (!producerAllowlist.isAllowed(envelope.eventType(), envelope.producer())) {
            telemetry.recordToolResultUnknownDlq("wrong_producer");
            log.warn("SECURITY_ALERT: rejected tool.execution.result_unknown from disallowed producer '{}' (eventId={})", envelope.producer(), envelope.eventId());
            throw new EventProducerNotAllowedException(envelope.eventType(), envelope.producer());
        }
    }
}
