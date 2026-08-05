package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyApprovalExpiredUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ApprovalExpiredEventPayload;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.ApprovalExpiredEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SPEC-TW-017: consumes {@code approval.expired.v1} messages routed to the
 * same {@code ticket-workflow.approval-events.v1} queue SPEC-TW-015/016
 * bind (see {@code RabbitMqConfiguration}). Invoked by {@link
 * ApprovalEventsDispatcher}, the queue's sole {@code @RabbitListener} — see
 * that class for why a per-event-type listener on this queue does not
 * work. Follows the same parse -> validate envelope -> validate event
 * type/version -> validate producer -> validate payload schema -> map ->
 * apply use case algorithm as {@code ApprovalRejectedEventConsumer}; save/
 * history/outbox/commit happen inside the use case's own {@code
 * @Transactional} boundary, and any exception thrown here propagates back
 * through the dispatcher to the same {@code
 * approvalEventsListenerContainerFactory} retry/DLQ interceptor.
 */
@Component
public class ApprovalExpiredEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiredEventConsumer.class);
    private static final String EVENT_TYPE = "approval.expired";
    private static final String EXPECTED_MAJOR_VERSION = "1";

    private final ConsumedEventValidator validator;
    private final EventProducerAllowlist producerAllowlist;
    private final ApprovalExpiredEventMapper mapper;
    private final ApplyApprovalExpiredUseCase useCase;
    private final ObjectMapper objectMapper;
    private final TicketTelemetry telemetry;

    public ApprovalExpiredEventConsumer(
        ConsumedEventValidator validator,
        EventProducerAllowlist producerAllowlist,
        ApprovalExpiredEventMapper mapper,
        ApplyApprovalExpiredUseCase useCase,
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
        ApprovalExpiredEventPayload payload = objectMapper.convertValue(envelope.payload(), ApprovalExpiredEventPayload.class);

        ApplyApprovalExpiredCommand command = mapper.toCommand(envelope, payload);
        telemetry.recordApprovalExpiredConsumed();
        useCase.applyApprovalExpired(command);
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            telemetry.recordApprovalExpiredDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(EVENT_TYPE, "malformed JSON body: " + e.getOriginalMessage());
        }
    }

    private void validateEventTypeAndVersion(ConsumedEventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            telemetry.recordApprovalExpiredDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unexpected eventType on the approval-events queue");
        }
        String majorVersion = envelope.eventVersion() == null ? "" : envelope.eventVersion().split("\\.")[0];
        if (!EXPECTED_MAJOR_VERSION.equals(majorVersion)) {
            telemetry.recordApprovalExpiredDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unsupported major version " + envelope.eventVersion());
        }
    }

    private void validateProducer(ConsumedEventEnvelope envelope) {
        if (!producerAllowlist.isAllowed(envelope.eventType(), envelope.producer())) {
            telemetry.recordApprovalExpiredDlq("wrong_producer");
            log.warn("SECURITY_ALERT: rejected approval.expired from disallowed producer '{}' (eventId={})", envelope.producer(), envelope.eventId());
            throw new EventProducerNotAllowedException(envelope.eventType(), envelope.producer());
        }
    }
}
