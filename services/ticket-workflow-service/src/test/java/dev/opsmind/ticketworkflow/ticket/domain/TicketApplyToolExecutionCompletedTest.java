package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolExecutionCompletedApplied;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-019 domain-rules §1: {@code EXECUTING -> VERIFYING} and its invariants. */
@Tag("unit")
class TicketApplyToolExecutionCompletedTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-08-04T18:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-04T17:55:00Z");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketToolExecutionCompletedApplied apply() {
        return Ticket.applyToolExecutionCompleted(
            TICKET_ID, TicketStatus.EXECUTING, ASSIGNEE_ID, 30L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "result-900", COMPLETED_AT, Map.of("resultCode", "DUO_ENROLLMENT_RESET"), "evt-completed-1", NOW
        );
    }

    @Test
    void shouldApplyToolExecutionCompletedOnAnExecutingTicket() {
        TicketToolExecutionCompletedApplied event = apply();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.EXECUTING);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.VERIFYING);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.actionId()).isEqualTo("act-100");
        assertThat(event.authorizationReference()).isEqualTo("auth-5678");
        assertThat(event.toolExecutionId()).isEqualTo("exec-500");
        assertThat(event.toolResultId()).isEqualTo("result-900");
        assertThat(event.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(event.resultSummary()).containsEntry("resultCode", "DUO_ENROLLMENT_RESET");
        assertThat(event.completedEventId()).isEqualTo("evt-completed-1");
        assertThat(event.transitionId()).isEqualTo("SM-021");
        assertThat(event.reasonCode()).isEqualTo("TOOL_EXECUTION_COMPLETED");
        assertThat(event.aggregateVersion()).isEqualTo(31L);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"EXECUTING"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanExecuting(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.applyToolExecutionCompleted(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 30L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "result-900", COMPLETED_AT, null, "evt-completed-1", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.VERIFYING);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.applyToolExecutionCompleted(
            TICKET_ID, TicketStatus.EXECUTING, null, 30L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "result-900", COMPLETED_AT, null, "evt-completed-1", NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldAllowANullResultSummary() {
        TicketToolExecutionCompletedApplied event = Ticket.applyToolExecutionCompleted(
            TICKET_ID, TicketStatus.EXECUTING, ASSIGNEE_ID, 30L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "result-900", COMPLETED_AT, null, "evt-completed-1", NOW
        );

        assertThat(event.resultSummary()).isNull();
    }
}
