package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketToolResultUnknownRecorded;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-021 domain-rules §1: {@code EXECUTING -> ESCALATED} and its invariants. */
@Tag("unit")
class TicketApplyToolResultUnknownTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-08-06T18:00:00Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-06T17:55:00Z");
    private static final String ASSIGNEE_ID = "sam.support";

    private TicketToolResultUnknownRecorded apply() {
        return Ticket.applyToolResultUnknown(
            TICKET_ID, TicketStatus.EXECUTING, ASSIGNEE_ID, 50L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TIMEOUT_AFTER_REQUEST_SENT", List.of("log-ref-1", "log-ref-2"), OBSERVED_AT, "evt-unknown-1", NOW
        );
    }

    @Test
    void shouldRecordAnUnknownResultAndEscalateAnExecutingTicket() {
        TicketToolResultUnknownRecorded event = apply();

        assertThat(event.previousStatus()).isEqualTo(TicketStatus.EXECUTING);
        assertThat(event.newStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(event.assigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(event.workflowId()).isEqualTo("wf-9000");
        assertThat(event.actionId()).isEqualTo("act-100");
        assertThat(event.authorizationReference()).isEqualTo("auth-5678");
        assertThat(event.toolExecutionId()).isEqualTo("exec-500");
        assertThat(event.unknownReason()).isEqualTo("TIMEOUT_AFTER_REQUEST_SENT");
        assertThat(event.evidenceReferences()).containsExactly("log-ref-1", "log-ref-2");
        assertThat(event.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(event.reconciliationRequired()).isTrue();
        assertThat(event.recordedEventId()).isEqualTo("evt-unknown-1");
        assertThat(event.transitionId()).isEqualTo("SM-024");
        assertThat(event.reasonCode()).isEqualTo("TOOL_RESULT_UNKNOWN");
        assertThat(event.aggregateVersion()).isEqualTo(51L);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"EXECUTING"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectEveryStatusOtherThanExecuting(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.applyToolResultUnknown(
            TICKET_ID, currentStatus, ASSIGNEE_ID, 50L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TIMEOUT_AFTER_REQUEST_SENT", List.of(), OBSERVED_AT, "evt-unknown-1", NOW
        ))
            .isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.targetStatus()).isEqualTo(TicketStatus.ESCALATED);
            });
    }

    @Test
    void shouldRejectAMissingAssignee() {
        assertThatThrownBy(() -> Ticket.applyToolResultUnknown(
            TICKET_ID, TicketStatus.EXECUTING, null, 50L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TIMEOUT_AFTER_REQUEST_SENT", List.of(), OBSERVED_AT, "evt-unknown-1", NOW
        )).isInstanceOf(TicketNotAssignedException.class);
    }

    @Test
    void shouldNormalizeANullEvidenceReferencesListToEmpty() {
        TicketToolResultUnknownRecorded event = Ticket.applyToolResultUnknown(
            TICKET_ID, TicketStatus.EXECUTING, ASSIGNEE_ID, 50L, "wf-9000", "act-100", "auth-5678",
            "exec-500", "TIMEOUT_AFTER_REQUEST_SENT", null, OBSERVED_AT, "evt-unknown-1", NOW
        );

        assertThat(event.evidenceReferences()).isEmpty();
    }
}
