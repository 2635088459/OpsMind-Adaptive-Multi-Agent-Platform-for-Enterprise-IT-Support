package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.opsmind.policygovernance.application.TicketApprovalRequiredEventHandler;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/** SPEC-PG-027: JSON parsing/validation at the consumer boundary. Mirrors {@code ToolApprovalRequiredEventConsumerTest}. */
@Tag("unit")
class TicketApprovalRequiredEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TicketApprovalRequiredEventHandler handler = Mockito.mock(TicketApprovalRequiredEventHandler.class);
    private final TicketApprovalRequiredEventConsumer consumer = new TicketApprovalRequiredEventConsumer(handler, objectMapper);

    private static final String VALID_BODY = """
        {
          "eventId": "evt-1",
          "eventType": "ticket.approval.required.v1",
          "producer": "ticket-workflow-service",
          "schemaVersion": 1,
          "aggregateId": "ticket-1",
          "ticketId": "ticket-1",
          "correlationId": "corr-1",
          "causationId": "cause-0",
          "occurredAt": "2026-08-23T00:00:00Z",
          "payload": {
            "ticketId": "ticket-1",
            "exceptionType": "SLA_EXCEPTION",
            "riskLevel": "HIGH",
            "inputHash": "hash-1",
            "constraints": []
          }
        }
        """;

    @Test
    void aValidMessageIsMappedAndHandedToTheHandler() {
        consumer.onMessage(VALID_BODY);

        ArgumentCaptor<RequestApprovalCommand> captor = ArgumentCaptor.forClass(RequestApprovalCommand.class);
        verify(handler).handle(anyString(), captor.capture());
        assertThat(captor.getValue().requestKey()).isEqualTo("ticket-1");
        assertThat(captor.getValue().approvalType()).isEqualTo(ApprovalType.TICKET_SLA_EXCEPTION);
    }

    @Test
    void malformedJsonIsRejectedRatherThanHandedToTheHandler() {
        assertThatThrownBy(() -> consumer.onMessage("not json"))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(handler, Mockito.never()).handle(anyString(), any());
    }

    @Test
    void anUnexpectedEventTypeIsRejected() {
        String wrongType = VALID_BODY.replace("ticket.approval.required.v1", "approval.granted.v1");

        assertThatThrownBy(() -> consumer.onMessage(wrongType))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(handler, Mockito.never()).handle(anyString(), any());
    }

    @Test
    void aMissingPayloadIsRejected() {
        String noPayload = """
            {
              "eventId": "evt-1",
              "eventType": "ticket.approval.required.v1",
              "producer": "ticket-workflow-service",
              "schemaVersion": 1,
              "aggregateId": "ticket-1",
              "correlationId": "corr-1",
              "occurredAt": "2026-08-23T00:00:00Z"
            }
            """;

        assertThatThrownBy(() -> consumer.onMessage(noPayload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }
}
