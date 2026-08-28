package com.opsmind.identity.infrastructure.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.opsmind.identity.application.service.ApprovalDecisionEventHandler;
import com.opsmind.identity.domain.breakglass.ApprovalOutcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** SPEC-UA-028: JSON parsing/validation at the consumer boundary, without Spring or a real broker. */
@Tag("unit")
class ApprovalDecisionEventConsumerTest {

    /**
     * A bare, otherwise-default {@code ObjectMapper} — this app's own real
     * Spring-managed bean defaults {@code FAIL_ON_UNKNOWN_PROPERTIES} to
     * {@code true} (confirmed via a real Testcontainers RabbitMQ round
     * trip, not assumed), so this test deliberately does NOT relax that
     * setting; {@code ConsumedEventEnvelope}/{@code ApprovalDecisionPayload}'s
     * own {@code @JsonIgnoreProperties(ignoreUnknown = true)} is what
     * makes a realistic message with extra fields (e.g. {@code decidedBy},
     * a conditional {@code ticketId}) parse correctly, matching production
     * exactly rather than a more lenient stand-in.
     */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ApprovalDecisionEventHandler handler = Mockito.mock(ApprovalDecisionEventHandler.class);
    private final ApprovalDecisionEventConsumer consumer = new ApprovalDecisionEventConsumer(handler, objectMapper);

    private static String body(String eventType, String approvalRequestId) {
        return """
            {
              "eventId": "evt-1",
              "eventType": "%s",
              "producer": "policy-approval-governance-service",
              "schemaVersion": 1,
              "aggregateId": "%s",
              "correlationId": "corr-1",
              "causationId": "cause-0",
              "occurredAt": "2026-08-23T00:00:00Z",
              "payload": {
                "approvalRequestId": "%s",
                "decidedBy": "approver-1",
                "reason": "looks fine"
              }
            }
            """.formatted(eventType, approvalRequestId, approvalRequestId);
    }

    @Test
    void aValidGrantedMessageIsMappedAndHandedToTheHandler() {
        consumer.onGranted(body("approval.granted.v1", "approval-ref-1"));

        verify(handler).handle("evt-1", "approval.granted.v1", "approval-ref-1", ApprovalOutcome.GRANTED, "corr-1");
    }

    @Test
    void aValidDeniedMessageIsMappedAndHandedToTheHandler() {
        consumer.onDenied(body("approval.denied.v1", "approval-ref-2"));

        verify(handler).handle(eq("evt-1"), eq("approval.denied.v1"), eq("approval-ref-2"), eq(ApprovalOutcome.DENIED), eq("corr-1"));
    }

    @Test
    void aValidExpiredMessageIsMappedAndHandedToTheHandler() {
        consumer.onExpired(body("approval.expired.v1", "approval-ref-3"));

        verify(handler).handle(eq("evt-1"), eq("approval.expired.v1"), eq("approval-ref-3"), eq(ApprovalOutcome.EXPIRED), eq("corr-1"));
    }

    @Test
    void malformedJsonIsRejectedRatherThanHandedToTheHandler() {
        assertThatThrownBy(() -> consumer.onGranted("not json")).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        Mockito.verifyNoInteractions(handler);
    }

    @Test
    void anUnexpectedEventTypeOnAQueueIsRejected() {
        assertThatThrownBy(() -> consumer.onGranted(body("approval.denied.v1", "approval-ref-4")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        Mockito.verifyNoInteractions(handler);
    }

    @Test
    void aMissingPayloadIsRejected() {
        String noPayload = """
            {
              "eventId": "evt-1",
              "eventType": "approval.granted.v1",
              "producer": "policy-approval-governance-service",
              "schemaVersion": 1,
              "aggregateId": "approval-ref-1",
              "correlationId": "corr-1",
              "occurredAt": "2026-08-23T00:00:00Z"
            }
            """;

        assertThatThrownBy(() -> consumer.onGranted(noPayload)).isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void aPayloadMissingApprovalRequestIdIsRejected() {
        String noApprovalRequestId = """
            {
              "eventId": "evt-1",
              "eventType": "approval.granted.v1",
              "producer": "policy-approval-governance-service",
              "schemaVersion": 1,
              "aggregateId": "approval-ref-1",
              "correlationId": "corr-1",
              "occurredAt": "2026-08-23T00:00:00Z",
              "payload": {"decidedBy": "approver-1"}
            }
            """;

        assertThatThrownBy(() -> consumer.onGranted(noApprovalRequestId)).isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }
}
