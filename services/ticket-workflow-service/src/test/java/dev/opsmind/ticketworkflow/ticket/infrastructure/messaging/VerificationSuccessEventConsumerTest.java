package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyVerificationSuccessCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyVerificationSuccessUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer.VerificationSuccessEventConsumer;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.VerificationSuccessEventMapper;
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

/** SPEC-TW-023: parse -> envelope validation -> type/version -> producer allowlist -> payload validation -> map -> apply, and the DLQ-worthy failure classifications. */
@Tag("unit")
class VerificationSuccessEventConsumerTest {

    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");
    private static final UUID RESOLUTION_CYCLE_ID = UUID.fromString("3d912886-9652-4d88-8a64-1297b50f14c7");

    private ConsumedEventValidator validator;
    private ApplyVerificationSuccessUseCase useCase;
    private TicketTelemetry telemetry;
    private VerificationSuccessEventConsumer consumer;

    @BeforeEach
    void setUp() {
        validator = mock(ConsumedEventValidator.class);
        useCase = mock(ApplyVerificationSuccessUseCase.class);
        telemetry = mock(TicketTelemetry.class);
        consumer = new VerificationSuccessEventConsumer(
            validator, new EventProducerAllowlist(), new VerificationSuccessEventMapper(), useCase,
            new ObjectMapper().findAndRegisterModules(), telemetry
        );
    }

    private String validBody() {
        return """
            {
              "eventId": "evt-verification-1",
              "eventType": "verification.completed",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-08T17:55:00Z",
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
                "result": "SUCCESS",
                "verificationEvidenceId": "evidence-900",
                "evidenceSummary": {"checkType": "LOGIN_TEST"},
                "completedAt": "2026-08-08T17:55:00Z"
              }
            }
            """.formatted(TICKET_ID, RESOLUTION_CYCLE_ID);
    }

    private String bodyWithProducer(String producer) {
        return validBody().replace("\"producer\": \"verification-service\"", "\"producer\": \"" + producer + "\"");
    }

    private String bodyWithEventType(String eventType) {
        return validBody().replace("\"eventType\": \"verification.completed\"", "\"eventType\": \"" + eventType + "\"");
    }

    private String bodyWithVersion(String version) {
        return validBody().replace("\"eventVersion\": \"1.0\"", "\"eventVersion\": \"" + version + "\"");
    }

    @Test
    void shouldProcessAValidMessageAndInvokeTheUseCase() {
        consumer.onMessage(validBody());

        ArgumentCaptor<ApplyVerificationSuccessCommand> captor = ArgumentCaptor.forClass(ApplyVerificationSuccessCommand.class);
        verify(useCase).applyVerificationSuccess(captor.capture());
        assertThat(captor.getValue().ticketId().value()).isEqualTo(TICKET_ID);
        assertThat(captor.getValue().eventId()).isEqualTo("evt-verification-1");
        assertThat(captor.getValue().verificationId()).isEqualTo("ver-1234");
        assertThat(captor.getValue().workflowId()).isEqualTo("wf-9000");
        assertThat(captor.getValue().resolutionCycleId()).isEqualTo(RESOLUTION_CYCLE_ID);
        assertThat(captor.getValue().attemptNumber()).isEqualTo(1);
        assertThat(captor.getValue().verificationEvidenceId()).isEqualTo("evidence-900");
        assertThat(captor.getValue().evidenceSummary()).containsEntry("checkType", "LOGIN_TEST");

        verify(validator).validateEnvelope(any());
        verify(validator).validatePayload(eq("verification.completed"), eq("1.0"), any());
        verify(telemetry).recordVerificationCompletedConsumed();
    }

    @Test
    void shouldRejectMalformedJsonAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage("{ not json")).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyVerificationSuccess(any());
    }

    @Test
    void shouldRejectAnUnexpectedEventTypeAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithEventType("verification.failed")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyVerificationSuccess(any());
    }

    @Test
    void shouldRejectAnUnsupportedMajorVersionAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithVersion("2.0")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyVerificationSuccess(any());
    }

    @Test
    void shouldRejectADisallowedProducer() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithProducer("some-other-service")))
            .isInstanceOf(EventProducerNotAllowedException.class);
        verify(useCase, never()).applyVerificationSuccess(any());
        verify(telemetry).recordVerificationCompletedDlq("wrong_producer");
    }

    @Test
    void shouldRejectAMalformedTicketIdAsSchemaInvalid() {
        String body = validBody().replace(TICKET_ID.toString(), "not-a-uuid");

        assertThatThrownBy(() -> consumer.onMessage(body)).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyVerificationSuccess(any());
    }
}
