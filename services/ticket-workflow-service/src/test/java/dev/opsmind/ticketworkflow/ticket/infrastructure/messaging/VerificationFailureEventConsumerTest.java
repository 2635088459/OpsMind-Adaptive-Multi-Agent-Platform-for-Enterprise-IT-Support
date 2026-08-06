package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationFailureCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyVerificationFailureUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer.VerificationFailureEventConsumer;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.VerificationFailureEventMapper;
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

/** SPEC-TW-024: parse -> envelope validation -> type/version -> producer allowlist -> payload validation -> map -> apply, and the DLQ-worthy failure classifications. */
@Tag("unit")
class VerificationFailureEventConsumerTest {

    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");

    private ConsumedEventValidator validator;
    private ApplyVerificationFailureUseCase useCase;
    private TicketTelemetry telemetry;
    private VerificationFailureEventConsumer consumer;

    @BeforeEach
    void setUp() {
        validator = mock(ConsumedEventValidator.class);
        useCase = mock(ApplyVerificationFailureUseCase.class);
        telemetry = mock(TicketTelemetry.class);
        consumer = new VerificationFailureEventConsumer(
            validator, new EventProducerAllowlist(), new VerificationFailureEventMapper(), useCase,
            new ObjectMapper().findAndRegisterModules(), telemetry
        );
    }

    private String validBody() {
        return """
            {
              "eventId": "evt-verification-failed-1",
              "eventType": "verification.failed",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-09T17:55:00Z",
              "producer": "verification-service",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": {
                "workflowId": "wf-9000",
                "resolutionCycleId": "%s",
                "verificationId": "ver-1234",
                "attemptNumber": 1,
                "failureCode": "LOGIN_STILL_FAILS",
                "failureClass": "RETRYABLE",
                "unsafe": false,
                "failedAt": "2026-08-09T17:55:00Z"
              }
            }
            """.formatted(TICKET_ID, RESOLUTION_CYCLE_ID);
    }

    private String bodyWithProducer(String producer) {
        return validBody().replace("\"producer\": \"verification-service\"", "\"producer\": \"" + producer + "\"");
    }

    private String bodyWithEventType(String eventType) {
        return validBody().replace("\"eventType\": \"verification.failed\"", "\"eventType\": \"" + eventType + "\"");
    }

    private String bodyWithVersion(String version) {
        return validBody().replace("\"eventVersion\": \"1.0\"", "\"eventVersion\": \"" + version + "\"");
    }

    @Test
    void shouldProcessAValidMessageAndInvokeTheUseCase() {
        consumer.onMessage(validBody());

        ArgumentCaptor<ApplyVerificationFailureCommand> captor = ArgumentCaptor.forClass(ApplyVerificationFailureCommand.class);
        verify(useCase).applyVerificationFailure(captor.capture());
        assertThat(captor.getValue().ticketId().value()).isEqualTo(TICKET_ID);
        assertThat(captor.getValue().eventId()).isEqualTo("evt-verification-failed-1");
        assertThat(captor.getValue().verificationId()).isEqualTo("ver-1234");
        assertThat(captor.getValue().workflowId()).isEqualTo("wf-9000");
        assertThat(captor.getValue().resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(captor.getValue().attemptNumber()).isEqualTo(1);
        assertThat(captor.getValue().failureCode()).isEqualTo("LOGIN_STILL_FAILS");
        assertThat(captor.getValue().failureClass()).isEqualTo("RETRYABLE");
        assertThat(captor.getValue().unsafeResult()).isFalse();

        verify(validator).validateEnvelope(any());
        verify(validator).validatePayload(eq("verification.failed"), eq("1.0"), any());
        verify(telemetry).recordVerificationFailedConsumed();
    }

    @Test
    void shouldRejectMalformedJsonAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage("{ not json")).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyVerificationFailure(any());
    }

    @Test
    void shouldRejectAnUnexpectedEventTypeAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithEventType("verification.completed")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyVerificationFailure(any());
    }

    @Test
    void shouldRejectAnUnsupportedMajorVersionAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithVersion("2.0")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyVerificationFailure(any());
    }

    @Test
    void shouldRejectADisallowedProducer() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithProducer("some-other-service")))
            .isInstanceOf(EventProducerNotAllowedException.class);
        verify(useCase, never()).applyVerificationFailure(any());
        verify(telemetry).recordVerificationFailedDlq("wrong_producer");
    }

    @Test
    void shouldRejectAMalformedTicketIdAsSchemaInvalid() {
        String body = validBody().replace(TICKET_ID.toString(), "not-a-uuid");

        assertThatThrownBy(() -> consumer.onMessage(body)).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyVerificationFailure(any());
    }
}
