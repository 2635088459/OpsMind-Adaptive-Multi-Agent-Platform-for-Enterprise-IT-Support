package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketTimelineViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-006 §7: the server resolves the view from the trusted principal only. */
@Tag("unit")
class TicketTimelineViewPolicyTest {

    private final TicketTimelineViewPolicy policy = new TicketTimelineViewPolicy();

    @Test
    void shouldResolveEmployeeToEmployeePublicView() {
        assertThat(policy.resolve(TicketTimelineFixtures.employeeActor("employee-123")))
            .isEqualTo(TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW);
    }

    @Test
    void shouldResolveSupportWithoutInternalScopeToSupportPublicView() {
        assertThat(policy.resolve(TicketTimelineFixtures.supportPublicActor("support-100")))
            .isEqualTo(TicketTimelineViewType.SUPPORT_PUBLIC_VIEW);
    }

    @Test
    void shouldResolveSupportWithInternalScopeToSupportInternalView() {
        assertThat(policy.resolve(TicketTimelineFixtures.supportInternalActor("support-100")))
            .isEqualTo(TicketTimelineViewType.SUPPORT_INTERNAL_VIEW);
    }

    @Test
    void shouldResolveAdminAndManagerAsSupportActorTypes() {
        ActorContext admin = new ActorContext("IT_ADMIN", "admin-1", "support-console", Set.of());
        ActorContext manager = new ActorContext("IT_MANAGER", "manager-1", "support-console", Set.of(TicketTimelineFixtures.INTERNAL_SCOPE));

        assertThat(policy.resolve(admin)).isEqualTo(TicketTimelineViewType.SUPPORT_PUBLIC_VIEW);
        assertThat(policy.resolve(manager)).isEqualTo(TicketTimelineViewType.SUPPORT_INTERNAL_VIEW);
    }

    @Test
    void shouldResolveAuditorToAuditorPolicyView() {
        assertThat(policy.resolve(TicketTimelineFixtures.auditorActor("auditor-1")))
            .isEqualTo(TicketTimelineViewType.AUDITOR_POLICY_VIEW);
    }

    @Test
    void shouldRejectUnknownActorType() {
        ActorContext unknown = new ActorContext("UNKNOWN_TYPE", "someone", "client", Set.of());

        assertThatThrownBy(() -> policy.resolve(unknown)).isInstanceOf(TicketAuthorizationException.class);
    }

    @Test
    void requiredScopeShouldMatchTheSpecForEveryView() {
        assertThat(policy.requiredScope(TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW)).isEqualTo("tickets:read:self");
        assertThat(policy.requiredScope(TicketTimelineViewType.SUPPORT_PUBLIC_VIEW)).isEqualTo("tickets:read:queue");
        assertThat(policy.requiredScope(TicketTimelineViewType.SUPPORT_INTERNAL_VIEW)).isEqualTo("tickets:read:queue");
        assertThat(policy.requiredScope(TicketTimelineViewType.AUDITOR_POLICY_VIEW)).isEqualTo("tickets:audit:timeline");
    }
}
