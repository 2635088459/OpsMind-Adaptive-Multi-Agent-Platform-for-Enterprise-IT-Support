package com.opsmind.identity.infrastructure.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.service.ApprovalDecisionEventHandler;
import com.opsmind.identity.config.RabbitConfig;
import com.opsmind.identity.domain.breakglass.ApprovalOutcome;
import com.opsmind.identity.infrastructure.messaging.contract.ApprovalDecisionPayload;
import com.opsmind.identity.infrastructure.messaging.contract.ConsumedEventEnvelope;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * SPEC-UA-028 (06-event-contracts §Consumed events: "Domain 06: approval or
 * break-glass approved/denied/expired facts for controlled privileged
 * flows"). This domain's first real inbound event consumers — bound to the
 * three queues {@link RabbitConfig} declares for policy-approval-governance-service's
 * own real {@code approval.granted.v1}/{@code approval.denied.v1}/{@code
 * approval.expired.v1} events. All three share the identical envelope/
 * payload shape and dispatch to the same {@link ApprovalDecisionEventHandler},
 * parameterized only by which {@link ApprovalOutcome} arrived — kept as
 * three real {@code @RabbitListener} methods (matching the platform-wide
 * one-queue-per-event-type convention every other service's own {@code
 * RabbitConfig} already uses) rather than three near-duplicate classes.
 */
@Component
public class ApprovalDecisionEventConsumer {

    private final ApprovalDecisionEventHandler handler;
    private final ObjectMapper objectMapper;

    public ApprovalDecisionEventConsumer(ApprovalDecisionEventHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.APPROVAL_GRANTED_EVENTS_QUEUE, containerFactory = "approvalDecisionEventsListenerContainerFactory")
    public void onGranted(String body) {
        onMessage(body, "approval.granted.v1", ApprovalOutcome.GRANTED);
    }

    @RabbitListener(queues = RabbitConfig.APPROVAL_DENIED_EVENTS_QUEUE, containerFactory = "approvalDecisionEventsListenerContainerFactory")
    public void onDenied(String body) {
        onMessage(body, "approval.denied.v1", ApprovalOutcome.DENIED);
    }

    @RabbitListener(queues = RabbitConfig.APPROVAL_EXPIRED_EVENTS_QUEUE, containerFactory = "approvalDecisionEventsListenerContainerFactory")
    public void onExpired(String body) {
        onMessage(body, "approval.expired.v1", ApprovalOutcome.EXPIRED);
    }

    private void onMessage(String body, String expectedEventType, ApprovalOutcome outcome) {
        ConsumedEventEnvelope envelope = parseEnvelope(body, expectedEventType);
        if (!expectedEventType.equals(envelope.eventType())) {
            throw new ConsumedEventSchemaInvalidException("unexpected eventType '" + envelope.eventType() + "' on the " + expectedEventType + " queue");
        }
        ApprovalDecisionPayload payload = parsePayload(envelope, expectedEventType);
        handler.handle(envelope.eventId(), expectedEventType, payload.approvalRequestId(), outcome, envelope.correlationId());
    }

    private ConsumedEventEnvelope parseEnvelope(String body, String expectedEventType) {
        try {
            return objectMapper.readValue(body, ConsumedEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new ConsumedEventSchemaInvalidException("malformed " + expectedEventType + " envelope: " + e.getOriginalMessage(), e);
        }
    }

    private ApprovalDecisionPayload parsePayload(ConsumedEventEnvelope envelope, String expectedEventType) {
        if (envelope.payload() == null) {
            throw new ConsumedEventSchemaInvalidException(expectedEventType + " envelope is missing its payload");
        }
        try {
            ApprovalDecisionPayload payload = objectMapper.convertValue(envelope.payload(), ApprovalDecisionPayload.class);
            if (payload.approvalRequestId() == null || payload.approvalRequestId().isBlank()) {
                throw new ConsumedEventSchemaInvalidException(expectedEventType + " payload is missing approvalRequestId");
            }
            return payload;
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(expectedEventType + " payload does not match the expected shape: " + e.getMessage(), e);
        }
    }
}
