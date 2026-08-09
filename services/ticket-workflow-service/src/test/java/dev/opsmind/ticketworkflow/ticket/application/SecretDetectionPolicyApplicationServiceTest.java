package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSecretDetectionCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSecretDetectionResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionPolicyDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.model.SecretDetectionDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SecretDetectionDecisionPort;
import dev.opsmind.ticketworkflow.ticket.application.service.SecretDetectionPolicyApplicationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-035 api-contract / domain-rules: caller admission, operation
 * recognition, and free-text classification, in that order — every outcome
 * is recorded through {@link SecretDetectionAuditRecorder}, and any
 * unexpected failure (including a failed decision-audit write) fails
 * closed rather than defaulting to {@code ALLOW}.
 */
@Tag("unit")
class SecretDetectionPolicyApplicationServiceTest {

    private static final String CALLER_REQUIRED_SCOPE = "internal:secret-detection:evaluate";

    private SecretDetectionDecisionPort decisionPort;
    private SecretDetectionPolicyApplicationService service;

    @BeforeEach
    void setUp() {
        decisionPort = mock(SecretDetectionDecisionPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(Instant.parse("2026-08-08T12:00:00Z"));

        TicketTelemetry telemetry = new TicketTelemetry(new SimpleMeterRegistry());
        SecretDetectionAuditRecorder auditRecorder = new SecretDetectionAuditRecorder(decisionPort, clock, telemetry);

        service = new SecretDetectionPolicyApplicationService(new SecretDetectionPolicy(), auditRecorder, telemetry);
    }

    @Test
    void shouldRejectACallerMissingTheRequiredInternalScope() {
        EvaluateSecretDetectionCommand command = command(caller(Set.of()), "ticket.command", "ordinary support text");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(decisionPort, never()).record(any());
    }

    @Test
    void shouldAllowOrdinaryCleanText() {
        EvaluateSecretDetectionCommand command = command(trustedCaller(), "ticket.command", "The account has been unlocked, please try again.");

        EvaluateSecretDetectionResult result = service.evaluate(command);

        assertThat(result.decision()).isEqualTo("ALLOW");
        assertThat(result.decisionCode()).isEqualTo(SecretDetectionDecisionCode.ALLOWED);
        assertThat(result.auditRequired()).isTrue();
        verify(decisionPort).record(argThatDecision("ALLOW", SecretDetectionDecisionCode.ALLOWED));
    }

    @Test
    void shouldAllowBlankOrMissingContent() {
        EvaluateSecretDetectionCommand command = command(trustedCaller(), "ticket.command", null);

        EvaluateSecretDetectionResult result = service.evaluate(command);

        assertThat(result.decision()).isEqualTo("ALLOW");
    }

    @Test
    void shouldDenyContentContainingAPrivateKeyBlock() {
        String content = "here is my key\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow==\n-----END RSA PRIVATE KEY-----";
        EvaluateSecretDetectionCommand command = command(trustedCaller(), "ticket.command", content);

        assertThatThrownBy(() -> service.evaluate(command))
            .isInstanceOf(SecretDetectionPolicyDeniedException.class)
            .satisfies(e -> assertThat(((SecretDetectionPolicyDeniedException) e).decisionCode()).isEqualTo(SecretDetectionDecisionCode.DENIED_PRIVATE_KEY_BLOCK))
            .hasMessageNotContaining("BEGIN RSA PRIVATE KEY");
        verify(decisionPort).record(argThatDecision("DENY", SecretDetectionDecisionCode.DENIED_PRIVATE_KEY_BLOCK));
    }

    @Test
    void shouldDenyContentContainingAPasswordAssignmentWithoutEchoingTheSecret() {
        String secret = "sUp3rSecr3tSauce";
        EvaluateSecretDetectionCommand command = command(trustedCaller(), "ticket.command", "password=" + secret);

        assertThatThrownBy(() -> service.evaluate(command))
            .isInstanceOf(SecretDetectionPolicyDeniedException.class)
            .hasMessageNotContaining(secret);
        verify(decisionPort).record(argThatDecision("DENY", SecretDetectionDecisionCode.DENIED_PASSWORD_ASSIGNMENT));
    }

    @Test
    void shouldAllowOrdinaryTextMentioningTheWordPassword() {
        EvaluateSecretDetectionCommand command = command(trustedCaller(), "ticket.command", "I forgot my password and need help resetting it.");

        assertThat(service.evaluate(command).decision()).isEqualTo("ALLOW");
    }

    @Test
    void shouldReturnConflictForAnUnrecognizedOperation() {
        EvaluateSecretDetectionCommand command = command(trustedCaller(), "ticket.read", "ordinary text");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SecretDetectionPolicyConflictException.class);
        verify(decisionPort).record(argThatDecision("DENY", SecretDetectionDecisionCode.OPERATION_NOT_SUPPORTED));
    }

    @Test
    void shouldFailClosedWhenTheRequiredDecisionAuditWriteFails() {
        doThrow(new IllegalStateException("db unavailable")).when(decisionPort).record(any());
        EvaluateSecretDetectionCommand command = command(trustedCaller(), "ticket.command", "ordinary text");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SecretDetectionFailClosedException.class);
    }

    @Test
    void aSecondaryFailClosedAuditFailureMustNotMaskTheOriginalFailClosedResponse() {
        doThrow(new IllegalStateException("db always unavailable")).when(decisionPort).record(any());
        EvaluateSecretDetectionCommand command = command(trustedCaller(), "ticket.command", "password=hunter2");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SecretDetectionFailClosedException.class);
    }

    private SecretDetectionDecisionEntry argThatDecision(String decision, String decisionCode) {
        return argThat(entry -> entry != null
            && entry.decision().equals(decision)
            && entry.decisionCode().equals(decisionCode));
    }

    private ActorContext trustedCaller() {
        return caller(Set.of(CALLER_REQUIRED_SCOPE));
    }

    private ActorContext caller(Set<String> scopes) {
        return new ActorContext("SERVICE", "internal-caller", "internal-client", scopes);
    }

    private EvaluateSecretDetectionCommand command(ActorContext caller, String operation, String content) {
        return new EvaluateSecretDetectionCommand(
            caller, "TCK-1001", "user-123", "IT_SUPPORT", operation, content, "correlation-1", "trace-1"
        );
    }
}
