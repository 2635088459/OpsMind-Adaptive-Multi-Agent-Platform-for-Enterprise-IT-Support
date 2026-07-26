package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class GetTicketViewPolicyTest {

    private final TicketViewPolicy policy = new TicketViewPolicy();

    @ParameterizedTest
    @CsvSource({
        "EMPLOYEE, EMPLOYEE_VIEW",
        "IT_SUPPORT, SUPPORT_VIEW",
        "IT_ADMIN, SUPPORT_VIEW",
        "IT_MANAGER, SUPPORT_VIEW",
        "AUDITOR, AUDITOR_VIEW"
    })
    void shouldResolveViewFromTrustedActorType(String actorType, TicketViewType expectedView) {
        ActorContext actor = new ActorContext(actorType, "subject-1", "client-1", Set.of());

        assertThat(policy.resolve(actor)).isEqualTo(expectedView);
    }

    @Test
    void shouldRejectUnknownActorType() {
        ActorContext actor = new ActorContext("UNKNOWN_ROLE", "subject-1", "client-1", Set.of());

        assertThatThrownBy(() -> policy.resolve(actor)).isInstanceOf(TicketAuthorizationException.class);
    }

    @Test
    void shouldMapEachViewToItsRequiredScope() {
        assertThat(policy.requiredScope(TicketViewType.EMPLOYEE_VIEW)).isEqualTo("tickets:read:self");
        assertThat(policy.requiredScope(TicketViewType.SUPPORT_VIEW)).isEqualTo("tickets:read:queue");
        assertThat(policy.requiredScope(TicketViewType.AUDITOR_VIEW)).isEqualTo("tickets:audit:read");
    }
}
