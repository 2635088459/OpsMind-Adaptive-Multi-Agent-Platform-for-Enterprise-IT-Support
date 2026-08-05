package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventProducerNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyAutoApprovedPolicyUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.consumer.PolicyActionAutoApprovedEventConsumer;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.EventProducerAllowlist;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper.PolicyActionAutoApprovedEventMapper;
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

/** SPEC-TW-018: parse -> envelope validation -> type/version -> producer allowlist -> payload validation -> map -> apply, and the DLQ-worthy failure classifications. */
@Tag("unit")
class PolicyActionAutoApprovedEventConsumerTest {

    private static final UUID TICKET_ID = UUID.fromString("6c2ad02e-c394-41fb-8e38-dfffd581a59d");

    private ConsumedEventValidator validator;
    private ApplyAutoApprovedPolicyUseCase useCase;
    private TicketTelemetry telemetry;
    private PolicyActionAutoApprovedEventConsumer consumer;

    @BeforeEach
    void setUp() {
        validator = mock(ConsumedEventValidator.class);
        useCase = mock(ApplyAutoApprovedPolicyUseCase.class);
        telemetry = mock(TicketTelemetry.class);
        consumer = new PolicyActionAutoApprovedEventConsumer(
            validator, new EventProducerAllowlist(), new PolicyActionAutoApprovedEventMapper(), useCase,
            new ObjectMapper().findAndRegisterModules(), telemetry
        );
    }

    private String validBody() {
        return """
            {
              "eventId": "evt-1",
              "eventType": "policy.action_auto_approved",
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
                "actionType": "REFRESH_USER_SESSION",
                "riskLevel": "LOW",
                "policyId": "policy-42",
                "policyVersion": "1.0",
                "policyDecisionId": "policy-dec-300",
                "decidedAt": "2026-08-03T17:50:00Z"
              }
            }
            """.formatted(TICKET_ID);
    }

    private String bodyWithProducer(String producer) {
        return validBody().replace("\"producer\": \"policy-approval-service\"", "\"producer\": \"" + producer + "\"");
    }

    private String bodyWithEventType(String eventType) {
        return validBody().replace("\"eventType\": \"policy.action_auto_approved\"", "\"eventType\": \"" + eventType + "\"");
    }

    private String bodyWithVersion(String version) {
        return validBody().replace("\"eventVersion\": \"1.0\"", "\"eventVersion\": \"" + version + "\"");
    }

    @Test
    void shouldProcessAValidMessageAndInvokeTheUseCase() {
        consumer.onMessage(validBody());

        ArgumentCaptor<ApplyAutoApprovedPolicyCommand> captor = ArgumentCaptor.forClass(ApplyAutoApprovedPolicyCommand.class);
        verify(useCase).applyAutoApprovedPolicy(captor.capture());
        assertThat(captor.getValue().ticketId().value()).isEqualTo(TICKET_ID);
        assertThat(captor.getValue().eventId()).isEqualTo("evt-1");
        assertThat(captor.getValue().workflowId()).isEqualTo("wf-9000");
        assertThat(captor.getValue().actionId()).isEqualTo("act-100");
        assertThat(captor.getValue().actionType()).isEqualTo("REFRESH_USER_SESSION");
        assertThat(captor.getValue().riskLevel()).isEqualTo(ApprovalRiskLevel.LOW);
        assertThat(captor.getValue().policyId()).isEqualTo("policy-42");
        assertThat(captor.getValue().policyVersion()).isEqualTo("1.0");
        assertThat(captor.getValue().policyDecisionId()).isEqualTo("policy-dec-300");

        verify(validator).validateEnvelope(any());
        verify(validator).validatePayload(eq("policy.action_auto_approved"), eq("1.0"), any());
        verify(telemetry).recordPolicyActionAutoApprovedConsumed();
    }

    @Test
    void shouldRejectMalformedJsonAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage("{ not json")).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyAutoApprovedPolicy(any());
    }

    @Test
    void shouldRejectAnUnexpectedEventTypeAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithEventType("approval.granted")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyAutoApprovedPolicy(any());
    }

    @Test
    void shouldRejectAnUnsupportedMajorVersionAsSchemaInvalid() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithVersion("2.0")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyAutoApprovedPolicy(any());
    }

    @Test
    void shouldRejectADisallowedProducer() {
        assertThatThrownBy(() -> consumer.onMessage(bodyWithProducer("some-other-service")))
            .isInstanceOf(EventProducerNotAllowedException.class);
        verify(useCase, never()).applyAutoApprovedPolicy(any());
        verify(telemetry).recordPolicyActionAutoApprovedDlq("wrong_producer");
    }

    @Test
    void shouldRejectAMalformedTicketIdAsSchemaInvalid() {
        String body = validBody().replace(TICKET_ID.toString(), "not-a-uuid");

        assertThatThrownBy(() -> consumer.onMessage(body)).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyAutoApprovedPolicy(any());
    }

    @Test
    void shouldRejectAnUnrecognizedRiskLevelAsSchemaInvalid() {
        String body = validBody().replace("\"riskLevel\": \"LOW\"", "\"riskLevel\": \"BOGUS\"");

        assertThatThrownBy(() -> consumer.onMessage(body)).isInstanceOf(ConsumedEventSchemaInvalidException.class);
        verify(useCase, never()).applyAutoApprovedPolicy(any());
    }
}
