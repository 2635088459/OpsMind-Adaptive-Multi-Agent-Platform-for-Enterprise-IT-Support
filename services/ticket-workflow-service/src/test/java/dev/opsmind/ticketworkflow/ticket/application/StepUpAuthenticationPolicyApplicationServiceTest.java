package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateStepUpAuthenticationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateStepUpAuthenticationResult;
import dev.opsmind.ticketworkflow.ticket.application.command.StepUpProof;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationRequiredException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.model.StepUpAuthenticationDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.StepUpAuthenticationDecisionPort;
import dev.opsmind.ticketworkflow.ticket.application.service.StepUpAuthenticationPolicyApplicationService;
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
 * SPEC-TW-036 api-contract / domain-rules: caller admission, operation
 * recognition, and step-up proof validity, in that order — every outcome
 * is recorded through {@link StepUpAuthenticationAuditRecorder}, and any
 * unexpected failure (including a failed decision-audit write) fails
 * closed rather than defaulting to {@code ALLOW}.
 */
@Tag("unit")
class StepUpAuthenticationPolicyApplicationServiceTest {

    private static final String CALLER_REQUIRED_SCOPE = "internal:step-up:evaluate";
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    private StepUpAuthenticationDecisionPort decisionPort;
    private StepUpAuthenticationPolicyApplicationService service;

    @BeforeEach
    void setUp() {
        decisionPort = mock(StepUpAuthenticationDecisionPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);

        TicketTelemetry telemetry = new TicketTelemetry(new SimpleMeterRegistry());
        StepUpAuthenticationAuditRecorder auditRecorder = new StepUpAuthenticationAuditRecorder(decisionPort, clock, telemetry);

        service = new StepUpAuthenticationPolicyApplicationService(new StepUpAuthenticationPolicy(), auditRecorder, clock, telemetry);
    }

    @Test
    void shouldRejectACallerMissingTheRequiredInternalScope() {
        EvaluateStepUpAuthenticationCommand command = command(caller(Set.of()), "ticket.cancel", validProof());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(decisionPort, never()).record(any());
    }

    @Test
    void shouldAllowARecognizedOperationWithAValidProof() {
        EvaluateStepUpAuthenticationCommand command = command(trustedCaller(), "ticket.cancel", validProof());

        EvaluateStepUpAuthenticationResult result = service.evaluate(command);

        assertThat(result.decision()).isEqualTo("ALLOW");
        assertThat(result.decisionCode()).isEqualTo(StepUpAuthenticationDecisionCode.ALLOWED);
        assertThat(result.auditRequired()).isTrue();
        verify(decisionPort).record(argThatDecision("ALLOW", StepUpAuthenticationDecisionCode.ALLOWED));
    }

    @Test
    void shouldDenyAMissingProof() {
        EvaluateStepUpAuthenticationCommand command = command(trustedCaller(), "ticket.cancel", null);

        assertThatThrownBy(() -> service.evaluate(command))
            .isInstanceOfSatisfying(StepUpAuthenticationRequiredException.class,
                e -> assertThat(e.decisionCode()).isEqualTo(StepUpAuthenticationDecisionCode.DENIED_STEP_UP_MISSING));
        verify(decisionPort).record(argThatDecision("DENY", StepUpAuthenticationDecisionCode.DENIED_STEP_UP_MISSING));
    }

    @Test
    void shouldDenyAnExpiredProof() {
        StepUpProof expired = new StepUpProof("proof-1", "MFA_TOTP", NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        EvaluateStepUpAuthenticationCommand command = command(trustedCaller(), "ticket.cancel", expired);

        assertThatThrownBy(() -> service.evaluate(command))
            .isInstanceOfSatisfying(StepUpAuthenticationRequiredException.class,
                e -> assertThat(e.decisionCode()).isEqualTo(StepUpAuthenticationDecisionCode.DENIED_STEP_UP_EXPIRED));
        verify(decisionPort).record(argThatDecision("DENY", StepUpAuthenticationDecisionCode.DENIED_STEP_UP_EXPIRED));
    }

    @Test
    void shouldDenyAMalformedProof() {
        StepUpProof malformed = new StepUpProof("", "MFA_TOTP", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        EvaluateStepUpAuthenticationCommand command = command(trustedCaller(), "ticket.cancel", malformed);

        assertThatThrownBy(() -> service.evaluate(command))
            .isInstanceOfSatisfying(StepUpAuthenticationRequiredException.class,
                e -> assertThat(e.decisionCode()).isEqualTo(StepUpAuthenticationDecisionCode.DENIED_STEP_UP_INVALID));
    }

    @Test
    void shouldReturnConflictForAnUnrecognizedOperation() {
        EvaluateStepUpAuthenticationCommand command = command(trustedCaller(), "ticket.read", validProof());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(StepUpAuthenticationPolicyConflictException.class);
        verify(decisionPort).record(argThatDecision("DENY", StepUpAuthenticationDecisionCode.OPERATION_NOT_SUPPORTED));
    }

    @Test
    void shouldFailClosedWhenTheRequiredDecisionAuditWriteFails() {
        doThrow(new IllegalStateException("db unavailable")).when(decisionPort).record(any());
        EvaluateStepUpAuthenticationCommand command = command(trustedCaller(), "ticket.cancel", validProof());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(StepUpAuthenticationFailClosedException.class);
    }

    @Test
    void aSecondaryFailClosedAuditFailureMustNotMaskTheOriginalFailClosedResponse() {
        doThrow(new IllegalStateException("db always unavailable")).when(decisionPort).record(any());
        EvaluateStepUpAuthenticationCommand command = command(trustedCaller(), "ticket.cancel", null);

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(StepUpAuthenticationFailClosedException.class);
    }

    private StepUpProof validProof() {
        return new StepUpProof("proof-1", "MFA_TOTP", NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    private StepUpAuthenticationDecisionEntry argThatDecision(String decision, String decisionCode) {
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

    private EvaluateStepUpAuthenticationCommand command(ActorContext caller, String operation, StepUpProof proof) {
        return new EvaluateStepUpAuthenticationCommand(
            caller, "TCK-1001", "user-123", "IT_SUPPORT", operation, proof, "correlation-1", "trace-1"
        );
    }
}
