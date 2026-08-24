package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.policygovernance.application.TicketApprovalRequiredEventHandler;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.config.RabbitConfig;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.TicketApprovalRequiredPayload;
import com.opsmind.policygovernance.infrastructure.messaging.mapper.TicketApprovalRequiredEventMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * SPEC-PG-027: 06's third real inbound event consumer, bound to {@code
 * RabbitConfig#TICKET_APPROVAL_EVENTS_QUEUE} (routing key {@code
 * ticket.approval.required.v1}) — structurally identical to {@link
 * ToolApprovalRequiredEventConsumer}, see that type's own javadoc.
 */
@Component
public class TicketApprovalRequiredEventConsumer {

    private static final String EXPECTED_EVENT_TYPE = "ticket.approval.required.v1";

    private final TicketApprovalRequiredEventHandler handler;
    private final ObjectMapper objectMapper;

    public TicketApprovalRequiredEventConsumer(TicketApprovalRequiredEventHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.TICKET_APPROVAL_EVENTS_QUEUE, containerFactory = "ticketApprovalEventsListenerContainerFactory")
    public void onMessage(String body) {
        ConsumedEventEnvelope envelope = parseEnvelope(body);
        if (!EXPECTED_EVENT_TYPE.equals(envelope.eventType())) {
            throw new ConsumedEventSchemaInvalidException(
                "unexpected eventType '" + envelope.eventType() + "' on the ticket-approval-events queue"
            );
        }
        TicketApprovalRequiredPayload payload = parsePayload(envelope);
        RequestApprovalCommand command = TicketApprovalRequiredEventMapper.toCommand(envelope, payload);
        handler.handle(envelope.eventId(), command);
    }

    private ConsumedEventEnvelope parseEnvelope(String body) {
        try {
            return objectMapper.readValue(body, ConsumedEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new ConsumedEventSchemaInvalidException("malformed ticket.approval.required.v1 envelope: " + e.getOriginalMessage(), e);
        }
    }

    private TicketApprovalRequiredPayload parsePayload(ConsumedEventEnvelope envelope) {
        if (envelope.payload() == null) {
            throw new ConsumedEventSchemaInvalidException("ticket.approval.required.v1 envelope is missing its payload");
        }
        try {
            return objectMapper.convertValue(envelope.payload(), TicketApprovalRequiredPayload.class);
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException("ticket.approval.required.v1 payload does not match the expected shape: " + e.getMessage(), e);
        }
    }
}
