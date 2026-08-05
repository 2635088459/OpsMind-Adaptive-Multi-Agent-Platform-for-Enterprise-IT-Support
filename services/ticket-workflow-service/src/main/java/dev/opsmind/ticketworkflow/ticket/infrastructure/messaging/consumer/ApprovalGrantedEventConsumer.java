package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyApprovalGrantedUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ApprovalGrantedEventPayload;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.ApprovalGrantedEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SPEC-TW-015: consumes {@code approval.granted.v1} messages routed to
 * {@code ticket-workflow.approval-events.v1} (bound in {@code
 * RabbitMqConfiguration}). Invoked by {@link ApprovalEventsDispatcher}, the
 * queue's sole {@code @RabbitListener} — see that class for why a
 * per-event-type listener on this queue does not work. Follows
 * 06-event-contracts §13's algorithm up through step 12 (parse → validate
 * envelope schema → validate event type/version → validate producer →
 * validate payload schema → map → apply use case); steps 13-17 (save/
 * history/outbox/commit) happen inside the use case's own {@code
 * @Transactional} boundary. Any exception thrown here propagates back
 * through the dispatcher to {@code approvalEventsListenerContainerFactory}'s
 * retry interceptor: {@link
 * dev.opsmind.ticketworkflow.ticket.application.exception.NonRetryableConsumedEventException}
 * subclasses reject straight to the DLQ, transient database exceptions are
 * retried a bounded number of times first.
 */
@Component
public class ApprovalGrantedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGrantedEventConsumer.class);
    private static final String EVENT_TYPE = "approval.granted";
    private static final String EXPECTED_MAJOR_VERSION = "1";

    private final ConsumedEventValidator validator;
    private final EventProducerAllowlist producerAllowlist;
    private final ApprovalGrantedEventMapper mapper;
    private final ApplyApprovalGrantedUseCase useCase;
    private final ObjectMapper objectMapper;
    private final TicketTelemetry telemetry;

    public ApprovalGrantedEventConsumer(
        ConsumedEventValidator validator,
        EventProducerAllowlist producerAllowlist,
        ApprovalGrantedEventMapper mapper,
        ApplyApprovalGrantedUseCase useCase,
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
        ApprovalGrantedEventPayload payload = objectMapper.convertValue(envelope.payload(), ApprovalGrantedEventPayload.class);

        ApplyApprovalGrantedCommand command = mapper.toCommand(envelope, payload);
        telemetry.recordApprovalGrantedConsumed();
        useCase.applyApprovalGranted(command);
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            telemetry.recordApprovalGrantedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(EVENT_TYPE, "malformed JSON body: " + e.getOriginalMessage());
        }
    }

    private void validateEventTypeAndVersion(ConsumedEventEnvelope envelope) {
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            telemetry.recordApprovalGrantedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unexpected eventType on the approval-events queue");
        }
        String majorVersion = envelope.eventVersion() == null ? "" : envelope.eventVersion().split("\\.")[0];
        if (!EXPECTED_MAJOR_VERSION.equals(majorVersion)) {
            telemetry.recordApprovalGrantedDlq("schema_invalid");
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unsupported major version " + envelope.eventVersion());
        }
    }

    private void validateProducer(ConsumedEventEnvelope envelope) {
        if (!producerAllowlist.isAllowed(envelope.eventType(), envelope.producer())) {
            telemetry.recordApprovalGrantedDlq("wrong_producer");
            log.warn("SECURITY_ALERT: rejected approval.granted from disallowed producer '{}' (eventId={})", envelope.producer(), envelope.eventId());
            throw new EventProducerNotAllowedException(envelope.eventType(), envelope.producer());
        }
    }
}
