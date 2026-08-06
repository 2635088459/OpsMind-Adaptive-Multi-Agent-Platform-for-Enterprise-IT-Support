package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SPEC-TW-023: the {@code ticket-workflow.verification-events.v1} queue is
 * declared with {@code x-single-active-consumer} (see {@code
 * RabbitMqConfiguration}) so a single ticket's verification-result events
 * are always processed in order by one consumer. As {@code
 * ApprovalEventsDispatcher}'s Javadoc documents in detail, that invariant
 * only holds if exactly one {@code @RabbitListener} is bound to the queue —
 * this dispatcher is therefore that queue's only listener, doing the
 * minimum parsing needed to read {@code eventType} and handing the raw,
 * unparsed body to the matching per-event-type consumer. SPEC-TW-023
 * registers {@link VerificationSuccessEventConsumer} for {@code
 * verification.completed}; SPEC-TW-024 adds {@link
 * VerificationFailureEventConsumer} for {@code verification.failed}.
 */
@Component
public class VerificationEventsDispatcher {

    private static final String VERIFICATION_COMPLETED = "verification.completed";
    private static final String VERIFICATION_FAILED = "verification.failed";

    private final ObjectMapper objectMapper;
    private final VerificationSuccessEventConsumer successConsumer;
    private final VerificationFailureEventConsumer failureConsumer;

    public VerificationEventsDispatcher(
        ObjectMapper objectMapper,
        VerificationSuccessEventConsumer successConsumer,
        VerificationFailureEventConsumer failureConsumer
    ) {
        this.objectMapper = objectMapper;
        this.successConsumer = successConsumer;
        this.failureConsumer = failureConsumer;
    }

    @RabbitListener(queues = "ticket-workflow.verification-events.v1", containerFactory = "verificationEventsListenerContainerFactory")
    public void onMessage(String body) {
        String eventType = readEventType(body);
        switch (eventType == null ? "" : eventType) {
            case VERIFICATION_COMPLETED -> successConsumer.onMessage(body);
            case VERIFICATION_FAILED -> failureConsumer.onMessage(body);
            default -> throw new ConsumedEventSchemaInvalidException(
                String.valueOf(eventType), "no consumer registered for this eventType on the verification-events queue"
            );
        }
    }

    private String readEventType(String body) {
        Map<String, Object> envelopeMap;
        try {
            envelopeMap = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new ConsumedEventSchemaInvalidException("unknown", "malformed JSON body: " + e.getOriginalMessage());
        }
        Object eventType = envelopeMap.get("eventType");
        return eventType instanceof String stringEventType ? stringEventType : null;
    }
}
