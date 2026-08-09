package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSupportQueueAuthorizationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSupportQueueAuthorizationResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.model.SupportQueueAuthorizationDecisionEntry;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationDecisionCode;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueAuthorizationDecisionPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueMembershipPort;
import dev.opsmind.ticketworkflow.ticket.application.service.SupportQueueAuthorizationApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-033 api-contract / domain-rules: caller admission, operation
 * recognition, target actor-type eligibility, context sufficiency, and
 * Support Queue membership, in that order — every rejection (and the
 * allow) is recorded through {@link SupportQueueAuthorizationAuditRecorder},
 * and any unexpected failure (including a failed audit write) fails closed
 * rather than defaulting to {@code ALLOW}.
 */
@Tag("unit")
class SupportQueueAuthorizationApplicationServiceTest {

    private static final UUID QUEUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SupportQueueMembershipPort membershipPort;
    private SupportQueueAuthorizationDecisionPort decisionPort;
    private SupportQueueAuthorizationApplicationService service;

    @BeforeEach
    void setUp() {
        membershipPort = mock(SupportQueueMembershipPort.class);
        decisionPort = mock(SupportQueueAuthorizationDecisionPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(Instant.parse("2026-08-08T12:00:00Z"));

        TicketTelemetry telemetry = new TicketTelemetry(new SimpleMeterRegistry());
        SupportQueueAuthorizationAuditRecorder auditRecorder = new SupportQueueAuthorizationAuditRecorder(decisionPort, clock, telemetry);

        service = new SupportQueueAuthorizationApplicationService(
            membershipPort, new SupportQueueAuthorizationPolicy(), auditRecorder, telemetry
        );
    }

    @Test
    void shouldRejectACallerMissingTheRequiredInternalScope() {
        EvaluateSupportQueueAuthorizationCommand command = command(caller(Set.of()), "IT_SUPPORT", "ticket.command", QUEUE_ID.toString());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(TicketAuthorizationException.class);
        verify(decisionPort, never()).record(any());
    }

    @Test
    void shouldAllowAQueueScopedActorWhoIsAMemberOfTheRequestedQueue() {
        when(membershipPort.isMember("user-123", SupportQueueId.of(QUEUE_ID))).thenReturn(true);
        EvaluateSupportQueueAuthorizationCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.command", QUEUE_ID.toString());

        EvaluateSupportQueueAuthorizationResult result = service.evaluate(command);

        assertThat(result.decision()).isEqualTo("ALLOW");
        assertThat(result.decisionCode()).isEqualTo(SupportQueueAuthorizationDecisionCode.ALLOWED);
        assertThat(result.auditRequired()).isTrue();
        verify(decisionPort).record(argThatDecision("ALLOW", SupportQueueAuthorizationDecisionCode.ALLOWED));
    }

    @Test
    void shouldDenyAnEmployeeActorTypeRegardlessOfMembership() {
        EvaluateSupportQueueAuthorizationCommand command = command(trustedCaller(), "EMPLOYEE", "ticket.command", QUEUE_ID.toString());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SupportQueueAuthorizationDeniedException.class);
        verify(membershipPort, never()).isMember(any(), any());
        verify(decisionPort).record(argThatDecision("DENY", SupportQueueAuthorizationDecisionCode.DENIED_ACTOR_TYPE));
    }

    @Test
    void shouldDenyAQueueScopedActorWhoIsNotAMemberOfTheRequestedQueue() {
        when(membershipPort.isMember("user-123", SupportQueueId.of(QUEUE_ID))).thenReturn(false);
        EvaluateSupportQueueAuthorizationCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.command", QUEUE_ID.toString());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SupportQueueAuthorizationDeniedException.class);
        verify(decisionPort).record(argThatDecision("DENY", SupportQueueAuthorizationDecisionCode.DENIED_SCOPE));
    }

    @Test
    void shouldReturnConflictForAnUnrecognizedOperation() {
        EvaluateSupportQueueAuthorizationCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.delete-everything", QUEUE_ID.toString());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SupportQueueAuthorizationConflictException.class);
        verify(membershipPort, never()).isMember(any(), any());
        verify(decisionPort).record(argThatDecision("DENY", SupportQueueAuthorizationDecisionCode.OPERATION_NOT_SUPPORTED));
    }

    @Test
    void shouldReturnConflictWhenAQueueScopedOperationHasNoSupportQueueContext() {
        EvaluateSupportQueueAuthorizationCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.command", null);

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SupportQueueAuthorizationConflictException.class);
        verify(membershipPort, never()).isMember(any(), any());
        verify(decisionPort).record(argThatDecision("DENY", SupportQueueAuthorizationDecisionCode.CONTEXT_REQUIRED));
    }

    @Test
    void shouldFailClosedWhenTheMembershipPortThrows() {
        when(membershipPort.isMember(any(), any())).thenThrow(new IllegalStateException("directory unavailable"));
        EvaluateSupportQueueAuthorizationCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.command", QUEUE_ID.toString());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SupportQueueAuthorizationFailClosedException.class);
        verify(decisionPort).record(argThatDecision("FAIL_CLOSED", SupportQueueAuthorizationDecisionCode.FAIL_CLOSED_UNEXPECTED_ERROR));
    }

    @Test
    void shouldFailClosedRatherThanReturnAllowWhenTheDecisionCannotBeDurablyRecorded() {
        when(membershipPort.isMember("user-123", SupportQueueId.of(QUEUE_ID))).thenReturn(true);
        doThrow(new IllegalStateException("db unavailable")).when(decisionPort).record(any());
        EvaluateSupportQueueAuthorizationCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.command", QUEUE_ID.toString());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SupportQueueAuthorizationFailClosedException.class);
    }

    @Test
    void aSecondaryFailClosedAuditFailureMustNotMaskTheOriginalFailClosedResponse() {
        when(membershipPort.isMember(any(), any())).thenThrow(new IllegalStateException("directory unavailable"));
        doThrow(new IllegalStateException("db also unavailable")).when(decisionPort).record(any());
        EvaluateSupportQueueAuthorizationCommand command = command(trustedCaller(), "IT_SUPPORT", "ticket.command", QUEUE_ID.toString());

        assertThatThrownBy(() -> service.evaluate(command)).isInstanceOf(SupportQueueAuthorizationFailClosedException.class);
    }

    private SupportQueueAuthorizationDecisionEntry argThatDecision(String decision, String decisionCode) {
        return org.mockito.ArgumentMatchers.argThat(entry -> entry != null
            && entry.decision().equals(decision)
            && entry.decisionCode().equals(decisionCode));
    }

    private static final String CALLER_REQUIRED_SCOPE = "internal:support-queue-authorization:evaluate";

    private ActorContext trustedCaller() {
        return caller(Set.of(CALLER_REQUIRED_SCOPE));
    }

    private ActorContext caller(Set<String> scopes) {
        return new ActorContext("SERVICE", "internal-caller", "internal-client", scopes);
    }

    private EvaluateSupportQueueAuthorizationCommand command(ActorContext caller, String targetActorType, String operation, String supportQueueId) {
        return new EvaluateSupportQueueAuthorizationCommand(
            caller, "TCK-1001", "user-123", targetActorType, operation,
            supportQueueId == null ? null : SupportQueueId.of(UUID.fromString(supportQueueId)),
            "correlation-1", "trace-1"
        );
    }
}
