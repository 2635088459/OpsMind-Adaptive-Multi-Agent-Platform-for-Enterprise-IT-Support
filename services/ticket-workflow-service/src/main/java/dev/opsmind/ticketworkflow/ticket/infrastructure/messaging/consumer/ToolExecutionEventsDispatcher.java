package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SPEC-TW-019: the {@code ticket-workflow.tool-execution-events.v1} queue is
 * declared with {@code x-single-active-consumer} (see {@code
 * RabbitMqConfiguration}) so a single ticket's tool-result events are always
 * processed in order by one consumer. As {@code ApprovalEventsDispatcher}'s
 * Javadoc documents in detail, that invariant only holds if exactly one
 * {@code @RabbitListener} is bound to the queue — this dispatcher is
 * therefore that queue's only listener, doing the minimum parsing needed to
 * read {@code eventType} and handing the raw, unparsed body to the matching
 * per-event-type consumer. SPEC-TW-019 registers {@link
 * ToolExecutionCompletedEventConsumer}; SPEC-TW-020 adds {@link
 * ToolExecutionFailedEventConsumer}; SPEC-TW-021 adds {@link
 * ToolResultUnknownEventConsumer}.
 */
@Component
public class ToolExecutionEventsDispatcher {

    private static final String TOOL_EXECUTION_COMPLETED = "tool.execution.completed";
    private static final String TOOL_EXECUTION_FAILED = "tool.execution.failed";
    /** The routing key is {@code tool.execution.result-unknown.v1} (SPEC-TW-021 asyncapi.yaml), but the envelope's own {@code eventType} field must satisfy the shared envelope schema's {@code ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$} pattern, which forbids hyphens — hence the underscore here, matching 06-event-contracts CON-012's event name. */
    private static final String TOOL_EXECUTION_RESULT_UNKNOWN = "tool.execution.result_unknown";

    private final ObjectMapper objectMapper;
    private final ToolExecutionCompletedEventConsumer completedConsumer;
    private final ToolExecutionFailedEventConsumer failedConsumer;
    private final ToolResultUnknownEventConsumer resultUnknownConsumer;

    public ToolExecutionEventsDispatcher(
        ObjectMapper objectMapper,
        ToolExecutionCompletedEventConsumer completedConsumer,
        ToolExecutionFailedEventConsumer failedConsumer,
        ToolResultUnknownEventConsumer resultUnknownConsumer
    ) {
        this.objectMapper = objectMapper;
        this.completedConsumer = completedConsumer;
        this.failedConsumer = failedConsumer;
        this.resultUnknownConsumer = resultUnknownConsumer;
    }

    @RabbitListener(queues = "ticket-workflow.tool-execution-events.v1", containerFactory = "toolExecutionEventsListenerContainerFactory")
    public void onMessage(String body) {
        String eventType = readEventType(body);
        switch (eventType == null ? "" : eventType) {
            case TOOL_EXECUTION_COMPLETED -> completedConsumer.onMessage(body);
            case TOOL_EXECUTION_FAILED -> failedConsumer.onMessage(body);
            case TOOL_EXECUTION_RESULT_UNKNOWN -> resultUnknownConsumer.onMessage(body);
            default -> throw new ConsumedEventSchemaInvalidException(
                String.valueOf(eventType), "no consumer registered for this eventType on the tool-execution-events queue"
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
