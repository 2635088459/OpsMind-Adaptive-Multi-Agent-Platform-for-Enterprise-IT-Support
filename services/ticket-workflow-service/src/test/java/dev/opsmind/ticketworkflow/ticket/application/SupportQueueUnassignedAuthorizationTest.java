package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-005 §9: {@code unassignedOnly=true} conflicts with an explicit {@code assignedAgent}. */
@Tag("unit")
class SupportQueueUnassignedAuthorizationTest {

    @Test
    void shouldAllowUnassignedOnlyAlone() {
        SupportQueueFilters filters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), null, true, Set.of(), null, null
        );

        assertThat(filters.unassignedOnly()).isTrue();
        assertThat(filters.assignedAgent()).isNull();
    }

    @Test
    void shouldAllowAssignedAgentAloneWithoutUnassignedOnly() {
        SupportQueueFilters filters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), "agent-200", false, Set.of(), null, null
        );

        assertThat(filters.unassignedOnly()).isFalse();
        assertThat(filters.assignedAgent()).isEqualTo("agent-200");
    }

    @Test
    void shouldRejectUnassignedOnlyCombinedWithNonBlankAssignedAgent() {
        assertThatThrownBy(() -> new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), "agent-200", true, Set.of(), null, null
        )).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void shouldTreatBlankAssignedAgentAsAbsentAndNotConflictWithUnassignedOnly() {
        SupportQueueFilters filters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), "   ", true, Set.of(), null, null
        );

        assertThat(filters.unassignedOnly()).isTrue();
    }
}
