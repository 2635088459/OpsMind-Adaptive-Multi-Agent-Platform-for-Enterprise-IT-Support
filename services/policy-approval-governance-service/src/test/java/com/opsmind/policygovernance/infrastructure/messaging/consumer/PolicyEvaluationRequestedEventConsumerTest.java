package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.opsmind.policygovernance.application.PolicyEvaluationRequestedEventHandler;
import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/** SPEC-PG-028: JSON parsing/validation at the consumer boundary. Mirrors {@code ToolApprovalRequiredEventConsumerTest}. */
@Tag("unit")
class PolicyEvaluationRequestedEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final PolicyEvaluationRequestedEventHandler handler = Mockito.mock(PolicyEvaluationRequestedEventHandler.class);
    private final PolicyEvaluationRequestedEventConsumer consumer = new PolicyEvaluationRequestedEventConsumer(handler, objectMapper);

    private static final String VALID_BODY = """
        {
          "eventId": "evt-1",
          "eventType": "policy.evaluation.requested.v1",
          "producer": "memory-knowledge-service",
          "schemaVersion": 1,
          "aggregateId": "mem-1",
          "ticketId": "ticket-1",
          "correlationId": "corr-1",
          "causationId": "cause-0",
          "occurredAt": "2026-08-23T00:00:00Z",
          "payload": {
            "decisionKey": "dk-1",
            "inputHash": "hash-1",
            "subjectType": "user",
            "subjectId": "user-1",
            "actionType": "READ",
            "sourceRequestId": "src-req-1",
            "ticketId": "ticket-1",
            "policyId": "policy-1"
          }
        }
        """;

    @Test
    void aValidMessageIsMappedAndHandedToTheHandler() {
        consumer.onMessage(VALID_BODY);

        ArgumentCaptor<EvaluateDecisionCommand> captor = ArgumentCaptor.forClass(EvaluateDecisionCommand.class);
        verify(handler).handle(anyString(), captor.capture());
        assertThat(captor.getValue().decisionKey()).isEqualTo("dk-1");
        assertThat(captor.getValue().policyId()).isEqualTo("policy-1");
    }

    @Test
    void malformedJsonIsRejectedRatherThanHandedToTheHandler() {
        assertThatThrownBy(() -> consumer.onMessage("not json"))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(handler, Mockito.never()).handle(anyString(), any());
    }

    @Test
    void anUnexpectedEventTypeIsRejected() {
        String wrongType = VALID_BODY.replace("policy.evaluation.requested.v1", "approval.granted.v1");

        assertThatThrownBy(() -> consumer.onMessage(wrongType))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(handler, Mockito.never()).handle(anyString(), any());
    }

    @Test
    void aMissingPayloadIsRejected() {
        String noPayload = """
            {
              "eventId": "evt-1",
              "eventType": "policy.evaluation.requested.v1",
              "producer": "memory-knowledge-service",
              "schemaVersion": 1,
              "aggregateId": "mem-1",
              "correlationId": "corr-1",
              "occurredAt": "2026-08-23T00:00:00Z"
            }
            """;

        assertThatThrownBy(() -> consumer.onMessage(noPayload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }
}
