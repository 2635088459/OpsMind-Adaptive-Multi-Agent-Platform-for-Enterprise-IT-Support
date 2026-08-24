package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.policygovernance.application.WorkflowApprovalRequiredEventHandler;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.config.RabbitConfig;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.WorkflowApprovalRequiredPayload;
import com.opsmind.policygovernance.infrastructure.messaging.mapper.WorkflowApprovalRequiredEventMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * SPEC-PG-026: 06's second real inbound event consumer, bound to {@code
 * RabbitConfig#WORKFLOW_APPROVAL_EVENTS_QUEUE} (routing key {@code
 * workflow.approval.required.v1}) — structurally identical to {@link
 * ToolApprovalRequiredEventConsumer}, see that type's own javadoc.
 */
@Component
public class WorkflowApprovalRequiredEventConsumer {

    private static final String EXPECTED_EVENT_TYPE = "workflow.approval.required.v1";

    private final WorkflowApprovalRequiredEventHandler handler;
    private final ObjectMapper objectMapper;

    public WorkflowApprovalRequiredEventConsumer(WorkflowApprovalRequiredEventHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.WORKFLOW_APPROVAL_EVENTS_QUEUE, containerFactory = "workflowApprovalEventsListenerContainerFactory")
    public void onMessage(String body) {
        ConsumedEventEnvelope envelope = parseEnvelope(body);
        if (!EXPECTED_EVENT_TYPE.equals(envelope.eventType())) {
            throw new ConsumedEventSchemaInvalidException(
                "unexpected eventType '" + envelope.eventType() + "' on the workflow-approval-events queue"
            );
        }
        WorkflowApprovalRequiredPayload payload = parsePayload(envelope);
        RequestApprovalCommand command = WorkflowApprovalRequiredEventMapper.toCommand(envelope, payload);
        handler.handle(envelope.eventId(), command);
    }

    private ConsumedEventEnvelope parseEnvelope(String body) {
        try {
            return objectMapper.readValue(body, ConsumedEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new ConsumedEventSchemaInvalidException("malformed workflow.approval.required.v1 envelope: " + e.getOriginalMessage(), e);
        }
    }

    private WorkflowApprovalRequiredPayload parsePayload(ConsumedEventEnvelope envelope) {
        if (envelope.payload() == null) {
            throw new ConsumedEventSchemaInvalidException("workflow.approval.required.v1 envelope is missing its payload");
        }
        try {
            return objectMapper.convertValue(envelope.payload(), WorkflowApprovalRequiredPayload.class);
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException("workflow.approval.required.v1 payload does not match the expected shape: " + e.getMessage(), e);
        }
    }
}
