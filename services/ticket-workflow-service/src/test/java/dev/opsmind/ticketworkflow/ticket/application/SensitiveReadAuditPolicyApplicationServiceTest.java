package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditPolicyDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.model.SensitiveReadAuditDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditDecisionRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SensitiveReadAuditPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SensitiveReadAuditDecisionPort;
import dev.opsmind.ticketworkflow.ticket.application.service.SensitiveReadAuditPolicyApplicationService;
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
 * SPEC-TW-034 api-contract / domain-rules: caller admission, operation
 * recognition, and target actor-type eligibility, in that order — every
 * outcome is recorded through {@link SensitiveReadAuditDecisionRecorder},
 * and any unexpected failure (including a failed decision-audit write)
 * fails closed rather than defaulting to {@code ALLOW}.
 */
@Tag("unit")
class SensitiveReadAuditPolicyApplicationServiceTest {

    private static final String CALLER_REQUIRED_SCOPE = "internal:sensitive-read-audit:evaluate";

    private SensitiveReadAuditDecisionPort decisionPort;
    private SensitiveReadAuditPolicyApplicationService service;

    @BeforeEach
    void setUp() {
        decisionPort = mock(SensitiveReadAuditDecisionPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(Instant.parse("2026-08-08T12:00:00Z"));

        TicketTelemetry telemetry = new TicketTelemetry(new SimpleMeterRegistry());
        SensitiveReadAuditDecisionRecorder auditRecorder = new SensitiveReadAuditDecisionRecorder(decisionPort, clock, telemetry);

        service = new SensitiveReadAuditPolicyApplicationService(new SensitiveReadAuditPolicy(), auditRecorder, telemetry);
    }

    @Test
    void shouldRejectACallerMissingTheRequiredInternalScope() {
        EvaluateSensitiveReadAuditCommand command = command(caller(Set.of()), "IT_SUPPORT", "ticket.read");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(decisionPort, never()).record(any());
    }

    @Test
    void shouldAllowARecognizedOperationForAReadEligibleActorType() {
        EvaluateSensitiveReadAuditCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.read");

        EvaluateSensitiveReadAuditResult result = service.evaluate(command);

        assertThat(result.decision()).isEqualTo("ALLOW");
        assertThat(result.decisionCode()).isEqualTo(SensitiveReadAuditDecisionCode.ALLOWED);
        assertThat(result.auditRequired()).isTrue();
        verify(decisionPort).record(argThatDecision("ALLOW", SensitiveReadAuditDecisionCode.ALLOWED));
    }

    @Test
    void shouldAllowTheTimelineReadOperationToo() {
        EvaluateSensitiveReadAuditCommand command = command(trustedCaller(), "AUDITOR", "ticket.timeline.read");

        EvaluateSensitiveReadAuditResult result = service.evaluate(command);

        assertThat(result.decision()).isEqualTo("ALLOW");
    }

    @Test
    void shouldDenyAnActorTypeThatIsNotReadEligible() {
        EvaluateSensitiveReadAuditCommand command = command(trustedCaller(), "SERVICE", "ticket.read");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SensitiveReadAuditPolicyDeniedException.class);
        verify(decisionPort).record(argThatDecision("DENY", SensitiveReadAuditDecisionCode.DENIED_ACTOR_TYPE));
    }

    @Test
    void shouldReturnConflictForAnUnrecognizedOperation() {
        EvaluateSensitiveReadAuditCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.command");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SensitiveReadAuditPolicyConflictException.class);
        verify(decisionPort).record(argThatDecision("DENY", SensitiveReadAuditDecisionCode.OPERATION_NOT_SUPPORTED));
    }

    @Test
    void shouldFailClosedWhenTheRequiredDecisionAuditWriteFails() {
        doThrow(new IllegalStateException("db unavailable")).when(decisionPort).record(any());
        EvaluateSensitiveReadAuditCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.read");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SensitiveReadAuditFailureException.class);
    }

    @Test
    void aSecondaryFailClosedAuditFailureMustNotMaskTheOriginalFailClosedResponse() {
        doThrow(new IllegalStateException("db always unavailable")).when(decisionPort).record(any());
        EvaluateSensitiveReadAuditCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.read");

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SensitiveReadAuditFailureException.class);
    }

    private SensitiveReadAuditDecisionEntry argThatDecision(String decision, String decisionCode) {
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

    private EvaluateSensitiveReadAuditCommand command(ActorContext caller, String targetActorType, String operation) {
        return new EvaluateSensitiveReadAuditCommand(
            caller, "TCK-1001", "user-123", targetActorType, operation, "correlation-1", "trace-1"
        );
    }
}
