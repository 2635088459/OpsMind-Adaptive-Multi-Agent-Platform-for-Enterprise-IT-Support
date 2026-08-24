package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.policygovernance.application.ToolApprovalRequiredEventHandler;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.config.RabbitConfig;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ToolApprovalRequiredPayload;
import com.opsmind.policygovernance.infrastructure.messaging.mapper.ToolApprovalRequiredEventMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * SPEC-PG-025: 06's first real inbound event consumer, bound to {@code
 * RabbitConfig#TOOL_APPROVAL_EVENTS_QUEUE} (routing key {@code
 * tool.approval.required.v1}). Deliberately thin — parse, validate the
 * envelope's own {@code eventType}, map, hand off to {@link
 * ToolApprovalRequiredEventHandler} — every actual business decision
 * (dedup, request creation) lives in the application layer, not here.
 */
@Component
public class ToolApprovalRequiredEventConsumer {

    private static final String EXPECTED_EVENT_TYPE = "tool.approval.required.v1";

    private final ToolApprovalRequiredEventHandler handler;
    private final ObjectMapper objectMapper;

    public ToolApprovalRequiredEventConsumer(ToolApprovalRequiredEventHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.TOOL_APPROVAL_EVENTS_QUEUE, containerFactory = "toolApprovalEventsListenerContainerFactory")
    public void onMessage(String body) {
        ConsumedEventEnvelope envelope = parseEnvelope(body);
        if (!EXPECTED_EVENT_TYPE.equals(envelope.eventType())) {
            throw new ConsumedEventSchemaInvalidException(
                "unexpected eventType '" + envelope.eventType() + "' on the tool-approval-events queue"
            );
        }
        ToolApprovalRequiredPayload payload = parsePayload(envelope);
        RequestApprovalCommand command = ToolApprovalRequiredEventMapper.toCommand(envelope, payload);
        handler.handle(envelope.eventId(), command);
    }

    private ConsumedEventEnvelope parseEnvelope(String body) {
        try {
            return objectMapper.readValue(body, ConsumedEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new ConsumedEventSchemaInvalidException("malformed tool.approval.required.v1 envelope: " + e.getOriginalMessage(), e);
        }
    }

    private ToolApprovalRequiredPayload parsePayload(ConsumedEventEnvelope envelope) {
        if (envelope.payload() == null) {
            throw new ConsumedEventSchemaInvalidException("tool.approval.required.v1 envelope is missing its payload");
        }
        try {
            return objectMapper.convertValue(envelope.payload(), ToolApprovalRequiredPayload.class);
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException("tool.approval.required.v1 payload does not match the expected shape: " + e.getMessage(), e);
        }
    }
}
