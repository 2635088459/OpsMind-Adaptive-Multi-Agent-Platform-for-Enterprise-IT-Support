package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyApprovalGrantedUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer.ApprovalGrantedEventConsumer;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.ApprovalGrantedEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** SPEC-TW-015: parse -> envelope validation -> type/version -> producer allowlist -> payload validation -> map -> apply, and the DLQ-worthy failure classifications. */
@Tag("unit")
class ApprovalGrantedEventConsumerTest {

    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");

    private ConsumedEventValidator validator;
    private ApplyApprovalGrantedUseCase useCase;
    private TicketTelemetry telemetry;
    private ApprovalGrantedEventConsumer consumer;

    @BeforeEach
    void setUp() {
        validator = mock(ConsumedEventValidator.class);
        useCase = mock(ApplyApprovalGrantedUseCase.class);
        telemetry = mock(TicketTelemetry.class);
        consumer = new ApprovalGrantedEventConsumer(
            validator, new EventProducerAllowlist(), new ApprovalGrantedEventMapper(), useCase,
            new ObjectMapper().findAndRegisterModules(), telemetry
        );
    }

    private String validBody() {
        return """
            {
              "eventId": "evt-1",
              "eventType": "approval.granted",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-03T17:50:00Z",
              "producer": "policy-approval-service",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": {
                "workflowId": "wf-9000",
                "actionId": "act-100",
                "actionType": "RESET_MFA",
                "approvalId": "appr-1234",
                "approvedByIdHash": "sha256:approver",
                "approvedAt": "2026-08-03T17:50:00Z",
                "expiresAt": "2026-08-03T18:30:00Z"
              }
            }
            """.formatted(TICKET_ID);
    }

    private String bodyWithProducer(String producer) {
        return validBody().replace("\"producer\": \"policy-approval-service\"", "\"producer\": \"" + producer + "\"");
    }

    private String bodyWithEventType(String eventType) {
        return validBody().replace("\"eventType\": \"approval.granted\"", "\"eventType\": \"" + eventType + "\"");
    }

    private String bodyWithVersion(String version) {
        return validBody().replace("\"eventVersion\": \"1.0\"", "\"eventVersion\": \"" + version + "\"");
    }

    @Test
    void shouldProcessAValidMessageAndInvokeTheUseCase() {
        consumer.onMessage(validBody());

        ArgumentCaptor<ApplyApprovalGrantedCommand> captor = ArgumentCaptor.forClass(ApplyApprovalGrantedCommand.class);
        verify(useCase).applyApprovalGranted(captor.capture());
        assertThat(captor.getValue().ticketId().value()).isEqualTo(TICKET_ID);
        assertThat(captor.getValue().eventId()).isEqualTo("evt-1");
        assertThat(captor.getValue().workflowId()).isEqualTo("wf-9000");
        assertThat(captor.getValue().actionId()).isEqualTo("act-100");
        assertThat(captor.getValue().actionType()).isEqualTo("RESET_MFA");
        assertThat(captor.getValue().approvalId()).isEqualTo("appr-1234");

        verify(validator).validateEnvelope(any());
        verify(validator).validatePayload(eq("approval.granted"), eq("1.0"), any());
        verify(telemetry).recordApprovalGrantedConsumed();
    }

    @Test
    void shouldRejectMalformedJsonAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage("{ not json")).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyApprovalGranted(any());
    }

    @Test
    void shouldRejectAnUnexpectedEventTypeAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithEventType("approval.rejected")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyApprovalGranted(any());
    }

    @Test
    void shouldRejectAnUnsupportedMajorVersionAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithVersion("2.0")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyApprovalGranted(any());
    }

    @Test
    void shouldRejectADisallowedProducer() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithProducer("some-other-service")))
            .isInstanceOf(EventProducerNotAllowedException.class);
        verify(useCase, never()).applyApprovalGranted(any());
        verify(telemetry).recordApprovalGrantedDlq("wrong_producer");
    }

    @Test
    void shouldRejectAMalformedTicketIdAsSchemaInvalid() {
        String body = validBody().replace(TICKET_ID.toString(), "not-a-uuid");

        assertThatThrownBy(() -> consumer.onMessage(body)).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyApprovalGranted(any());
    }
}
