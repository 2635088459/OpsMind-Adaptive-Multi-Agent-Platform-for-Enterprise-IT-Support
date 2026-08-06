package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyToolExecutionCompletedCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyToolExecutionCompletedUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer.ToolExecutionCompletedEventConsumer;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.ToolExecutionCompletedEventMapper;
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

/** SPEC-TW-019: parse -> envelope validation -> type/version -> producer allowlist -> payload validation -> map -> apply, and the DLQ-worthy failure classifications. */
@Tag("unit")
class ToolExecutionCompletedEventConsumerTest {

    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");

    private ConsumedEventValidator validator;
    private ApplyToolExecutionCompletedUseCase useCase;
    private TicketTelemetry telemetry;
    private ToolExecutionCompletedEventConsumer consumer;

    @BeforeEach
    void setUp() {
        validator = mock(ConsumedEventValidator.class);
        useCase = mock(ApplyToolExecutionCompletedUseCase.class);
        telemetry = mock(TicketTelemetry.class);
        consumer = new ToolExecutionCompletedEventConsumer(
            validator, new EventProducerAllowlist(), new ToolExecutionCompletedEventMapper(), useCase,
            new ObjectMapper().findAndRegisterModules(), telemetry
        );
    }

    private String validBody() {
        return """
            {
              "eventId": "evt-completed-1",
              "eventType": "tool.execution.completed",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-04T17:55:00Z",
              "producer": "tool-gateway-service",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": {
                "workflowId": "wf-9000",
                "actionId": "act-100",
                "actionType": "RESET_MFA",
                "authorizationReference": "auth-5678",
                "toolExecutionId": "exec-500",
                "toolResultId": "result-900",
                "completedAt": "2026-08-04T17:55:00Z",
                "resultSummary": {"resultCode": "DUO_ENROLLMENT_RESET"}
              }
            }
            """.formatted(TICKET_ID);
    }

    private String bodyWithProducer(String producer) {
        return validBody().replace("\"producer\": \"tool-gateway-service\"", "\"producer\": \"" + producer + "\"");
    }

    private String bodyWithEventType(String eventType) {
        return validBody().replace("\"eventType\": \"tool.execution.completed\"", "\"eventType\": \"" + eventType + "\"");
    }

    private String bodyWithVersion(String version) {
        return validBody().replace("\"eventVersion\": \"1.0\"", "\"eventVersion\": \"" + version + "\"");
    }

    @Test
    void shouldProcessAValidMessageAndInvokeTheUseCase() {
        consumer.onMessage(validBody());

        ArgumentCaptor<ApplyToolExecutionCompletedCommand> captor = ArgumentCaptor.forClass(ApplyToolExecutionCompletedCommand.class);
        verify(useCase).applyToolExecutionCompleted(captor.capture());
        assertThat(captor.getValue().ticketId().value()).isEqualTo(TICKET_ID);
        assertThat(captor.getValue().eventId()).isEqualTo("evt-completed-1");
        assertThat(captor.getValue().workflowId()).isEqualTo("wf-9000");
        assertThat(captor.getValue().actionId()).isEqualTo("act-100");
        assertThat(captor.getValue().authorizationReference()).isEqualTo("auth-5678");
        assertThat(captor.getValue().toolExecutionId()).isEqualTo("exec-500");
        assertThat(captor.getValue().toolResultId()).isEqualTo("result-900");
        assertThat(captor.getValue().resultSummary()).containsEntry("resultCode", "DUO_ENROLLMENT_RESET");

        verify(validator).validateEnvelope(any());
        verify(validator).validatePayload(eq("tool.execution.completed"), eq("1.0"), any());
        verify(telemetry).recordToolExecutionCompletedConsumed();
    }

    @Test
    void shouldRejectMalformedJsonAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage("{ not json")).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyToolExecutionCompleted(any());
    }

    @Test
    void shouldRejectAnUnexpectedEventTypeAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithEventType("tool.execution.failed")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyToolExecutionCompleted(any());
    }

    @Test
    void shouldRejectAnUnsupportedMajorVersionAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithVersion("2.0")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyToolExecutionCompleted(any());
    }

    @Test
    void shouldRejectADisallowedProducer() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithProducer("some-other-service")))
            .isInstanceOf(EventProducerNotAllowedException.class);
        verify(useCase, never()).applyToolExecutionCompleted(any());
        verify(telemetry).recordToolExecutionCompletedDlq("wrong_producer");
    }

    @Test
    void shouldRejectAMalformedTicketIdAsSchemaInvalid() {
        String body = validBody().replace(TICKET_ID.toString(), "not-a-uuid");

        assertThatThrownBy(() -> consumer.onMessage(body)).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyToolExecutionCompleted(any());
    }
}
