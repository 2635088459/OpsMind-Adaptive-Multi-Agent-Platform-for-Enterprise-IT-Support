package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SPEC-TW-016: the {@code ticket-workflow.approval-events.v1} queue is
 * declared with {@code x-single-active-consumer} (see {@code
 * RabbitMqConfiguration}) so a single ticket's approval-decision events are
 * always processed in order by one consumer. That invariant only holds if
 * exactly one {@code @RabbitListener} is bound to the queue — once a
 * message is enqueued, RabbitMQ delivers it to whichever consumer channel
 * is currently active regardless of which routing key put it there, so two
 * independent {@code @RabbitListener} methods on the same queue would each
 * receive an unpredictable mix of every event type, not just their own
 * (this is exactly what happened when {@code ApprovalRejectedEventConsumer}
 * first shipped with its own listener alongside SPEC-TW-015's {@code
 * ApprovalGrantedEventConsumer}: whichever consumer RabbitMQ elected active
 * received 100% of the queue's traffic, including the other event type,
 * and rejected it as schema-invalid).
 * <p>
 * This dispatcher is therefore the queue's only {@code @RabbitListener}. It
 * does the minimum parsing needed to read {@code eventType} and hands the
 * raw, unparsed body to the matching per-event-type consumer, which then
 * runs its own complete parse -> validate -> map -> apply pipeline
 * unchanged (so each consumer's own unit tests, which call {@code
 * onMessage} directly and never touch this dispatcher, keep working
 * as-is). SPEC-TW-017 registers {@link ApprovalExpiredEventConsumer} here;
 * SPEC-TW-018 registers {@link PolicyActionAutoApprovedEventConsumer}.
 */
@Component
public class ApprovalEventsDispatcher {

    private static final String APPROVAL_GRANTED = "approval.granted";
    private static final String APPROVAL_REJECTED = "approval.rejected";
    private static final String APPROVAL_EXPIRED = "approval.expired";
    /** The routing key is {@code policy.action-auto-approved.v1} (SPEC-TW-018 asyncapi.yaml), but the envelope's own {@code eventType} field must satisfy the shared envelope schema's {@code ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$} pattern, which forbids hyphens — hence the underscore here, matching 06-event-contracts CON-009's event name. */
    private static final String POLICY_ACTION_AUTO_APPROVED = "policy.action_auto_approved";

    private final ObjectMapper objectMapper;
    private final ApprovalGrantedEventConsumer grantedConsumer;
    private final ApprovalRejectedEventConsumer rejectedConsumer;
    private final ApprovalExpiredEventConsumer expiredConsumer;
    private final PolicyActionAutoApprovedEventConsumer autoApprovedConsumer;

    public ApprovalEventsDispatcher(
        ObjectMapper objectMapper,
        ApprovalGrantedEventConsumer grantedConsumer,
        ApprovalRejectedEventConsumer rejectedConsumer,
        ApprovalExpiredEventConsumer expiredConsumer,
        PolicyActionAutoApprovedEventConsumer autoApprovedConsumer
    ) {
        this.objectMapper = objectMapper;
        this.grantedConsumer = grantedConsumer;
        this.rejectedConsumer = rejectedConsumer;
        this.expiredConsumer = expiredConsumer;
        this.autoApprovedConsumer = autoApprovedConsumer;
    }

    @RabbitListener(queues = "ticket-workflow.approval-events.v1", containerFactory = "approvalEventsListenerContainerFactory")
    public void onMessage(String body) {
        String eventType = readEventType(body);
        switch (eventType == null ? "" : eventType) {
            case APPROVAL_GRANTED -> grantedConsumer.onMessage(body);
            case APPROVAL_REJECTED -> rejectedConsumer.onMessage(body);
            case APPROVAL_EXPIRED -> expiredConsumer.onMessage(body);
            case POLICY_ACTION_AUTO_APPROVED -> autoApprovedConsumer.onMessage(body);
            default -> throw new ConsumedEventSchemaInvalidException(
                String.valueOf(eventType), "no consumer registered for this eventType on the approval-events queue"
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
