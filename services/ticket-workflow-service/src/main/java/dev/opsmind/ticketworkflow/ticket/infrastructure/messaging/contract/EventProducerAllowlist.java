package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 11-security §22 Event Trust Validation: broker authentication alone does
 * not prove business validity, so every consumed event type is checked
 * against a fixed producer allowlist. A mismatch is {@code
 * EVENT_PRODUCER_NOT_ALLOWED} — immediate DLQ plus a security alert.
 */
@Component
public class EventProducerAllowlist {

    private static final Map<String, String> ALLOWED_PRODUCERS = Map.of(
        "approval.granted", "policy-approval-service",
        "approval.rejected", "policy-approval-service",
        "approval.expired", "policy-approval-service",
        "policy.action_auto_approved", "policy-approval-service"
    );

    public boolean isAllowed(String eventType, String producer) {
        String allowedProducer = ALLOWED_PRODUCERS.get(eventType);
        return allowedProducer != null && allowedProducer.equals(producer);
    }
}
