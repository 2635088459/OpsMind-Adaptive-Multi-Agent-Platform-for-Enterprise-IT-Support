package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.policygovernance.application.PolicyEvaluationRequestedEventHandler;
import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.config.RabbitConfig;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.PolicyEvaluationRequestedPayload;
import com.opsmind.policygovernance.infrastructure.messaging.mapper.PolicyEvaluationRequestedEventMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * SPEC-PG-028: 06's fourth real inbound event consumer, bound to {@code
 * RabbitConfig#POLICY_EVALUATION_EVENTS_QUEUE} (routing key {@code
 * policy.evaluation.requested.v1}) — structurally identical to {@link
 * ToolApprovalRequiredEventConsumer}, see that type's own javadoc.
 */
@Component
public class PolicyEvaluationRequestedEventConsumer {

    private static final String EXPECTED_EVENT_TYPE = "policy.evaluation.requested.v1";

    private final PolicyEvaluationRequestedEventHandler handler;
    private final ObjectMapper objectMapper;

    public PolicyEvaluationRequestedEventConsumer(PolicyEvaluationRequestedEventHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.POLICY_EVALUATION_EVENTS_QUEUE, containerFactory = "policyEvaluationEventsListenerContainerFactory")
    public void onMessage(String body) {
        ConsumedEventEnvelope envelope = parseEnvelope(body);
        if (!EXPECTED_EVENT_TYPE.equals(envelope.eventType())) {
            throw new ConsumedEventSchemaInvalidException(
                "unexpected eventType '" + envelope.eventType() + "' on the policy-evaluation-events queue"
            );
        }
        PolicyEvaluationRequestedPayload payload = parsePayload(envelope);
        EvaluateDecisionCommand command = PolicyEvaluationRequestedEventMapper.toCommand(envelope, payload);
        handler.handle(envelope.eventId(), command);
    }

    private ConsumedEventEnvelope parseEnvelope(String body) {
        try {
            return objectMapper.readValue(body, ConsumedEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new ConsumedEventSchemaInvalidException("malformed policy.evaluation.requested.v1 envelope: " + e.getOriginalMessage(), e);
        }
    }

    private PolicyEvaluationRequestedPayload parsePayload(ConsumedEventEnvelope envelope) {
        if (envelope.payload() == null) {
            throw new ConsumedEventSchemaInvalidException("policy.evaluation.requested.v1 envelope is missing its payload");
        }
        try {
            return objectMapper.convertValue(envelope.payload(), PolicyEvaluationRequestedPayload.class);
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException("policy.evaluation.requested.v1 payload does not match the expected shape: " + e.getMessage(), e);
        }
    }
}
