package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineResponse;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineResponse;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItemType;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-006 §19/§20: item mapping — actor labels, summaries, and per-view field sets. */
@Tag("unit")
class TicketTimelineMappingTest {

    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");

    private final EmployeeTimelineApiMapper employeeMapper = new EmployeeTimelineApiMapper();
    private final SupportTimelineApiMapper supportMapper = new SupportTimelineApiMapper(new RequesterPseudonymizer(new TicketWorkflowProperties(
        "unit-test-secret", "unit-test-cursor-secret",
        new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
    )));

    private TicketTimelineResult resultWith(TicketTimelineViewType viewType, List<TicketTimelineItem> items) {
        return new TicketTimelineResult(TICKET_ID, "INC-2048", viewType, items, 50, false, null, NOW);
    }

    @Test
    void employeeViewShouldLabelTheirOwnActionsAsYou() {
        TicketTimelineItem item = TicketTimelineFixtures.publicRequesterMessageItem(UUID.randomUUID(), NOW, "hello");
        EmployeeTimelineResponse response = employeeMapper.toResponse(resultWith(TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW, List.of(item)));

        assertThat(response.items().get(0).actor().type()).isEqualTo("EMPLOYEE");
        assertThat(response.items().get(0).actor().displayLabel()).isEqualTo("You");
        assertThat(response.items().get(0).summary()).isEqualTo("You added a message");
    }

    @Test
    void employeeViewShouldNormalizeAnySupportActorTypeToItSupport() {
        TicketTimelineItem statusItem = TicketTimelineFixtures.statusChangedItem(UUID.randomUUID(), NOW, "NEW", "INVESTIGATING");
        EmployeeTimelineResponse response = employeeMapper.toResponse(resultWith(TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW, List.of(statusItem)));

        assertThat(response.items().get(0).actor().type()).isEqualTo("IT_SUPPORT");
        assertThat(response.items().get(0).actor().displayLabel()).isEqualTo("IT Support");
        assertThat(response.items().get(0).summary()).isEqualTo("Status changed from NEW to INVESTIGATING");
    }

    @Test
    void employeeInitialStatusChangeSummaryShouldOmitFromStatus() {
        TicketTimelineItem statusItem = TicketTimelineFixtures.statusChangedItem(UUID.randomUUID(), NOW, null, "NEW");
        EmployeeTimelineResponse response = employeeMapper.toResponse(resultWith(TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW, List.of(statusItem)));

        assertThat(response.items().get(0).summary()).isEqualTo("Status changed to NEW");
    }

    @Test
    void employeeResponseShouldNotContainAnyActorRefField() throws Exception {
        TicketTimelineItem item = TicketTimelineFixtures.ticketCreatedItem(TICKET_ID, NOW);
        EmployeeTimelineResponse response = employeeMapper.toResponse(resultWith(TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW, List.of(item)));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertThat(json).doesNotContain("actorRef");
        assertThat(json).doesNotContain("transitionId");
        assertThat(json).doesNotContain("reasonCode");
    }

    @Test
    void supportViewShouldLabelEmployeeActorAsRequesterAndPseudonymizeActorRef() {
        TicketTimelineItem item = TicketTimelineFixtures.publicRequesterMessageItem(UUID.randomUUID(), NOW, "hello");
        SupportTimelineResponse response = supportMapper.toResponse(resultWith(TicketTimelineViewType.SUPPORT_PUBLIC_VIEW, List.of(item)));

        assertThat(response.items().get(0).actor().type()).isEqualTo("EMPLOYEE");
        assertThat(response.items().get(0).actor().displayLabel()).isEqualTo("Requester");
        assertThat(response.items().get(0).actor().actorRef()).isNotNull().isNotEqualTo(TicketTimelineFixtures.DEFAULT_REQUESTER);
        assertThat(response.items().get(0).summary()).isEqualTo("Requester added a message");
    }

    @Test
    void supportViewShouldLabelSupportActorAsSupportAgent() {
        TicketTimelineItem item = TicketTimelineFixtures.internalSupportNoteItem(UUID.randomUUID(), NOW, "note");
        SupportTimelineResponse response = supportMapper.toResponse(resultWith(TicketTimelineViewType.SUPPORT_INTERNAL_VIEW, List.of(item)));

        assertThat(response.items().get(0).actor().type()).isEqualTo("IT_SUPPORT");
        assertThat(response.items().get(0).actor().displayLabel()).isEqualTo("Support agent");
        assertThat(response.items().get(0).summary()).isEqualTo("Internal support note added");
        assertThat(response.items().get(0).visibility()).isEqualTo("INTERNAL");
    }

    @Test
    void supportViewShouldIncludeTransitionIdAndReasonCodeForStatusChanges() {
        TicketTimelineItem item = TicketTimelineFixtures.statusChangedItem(UUID.randomUUID(), NOW, "NEW", "INVESTIGATING");
        SupportTimelineResponse response = supportMapper.toResponse(resultWith(TicketTimelineViewType.SUPPORT_INTERNAL_VIEW, List.of(item)));

        assertThat(response.items().get(0).metadata().transitionId()).isEqualTo("SM-003");
        assertThat(response.items().get(0).metadata().reasonCode()).isEqualTo("INVESTIGATION_STARTED");
    }

    @Test
    void itemTypeRankShouldOrderTicketCreatedBeforeStatusBeforeMessages() {
        assertThat(TicketTimelineItemType.TICKET_CREATED.itemTypeRank()).isEqualTo(0);
        assertThat(TicketTimelineItemType.STATUS_CHANGED.itemTypeRank()).isEqualTo(1);
        assertThat(TicketTimelineItemType.PUBLIC_REQUESTER_MESSAGE.itemTypeRank()).isEqualTo(2);
        assertThat(TicketTimelineItemType.PUBLIC_SUPPORT_MESSAGE.itemTypeRank()).isEqualTo(3);
        assertThat(TicketTimelineItemType.INTERNAL_SUPPORT_NOTE.itemTypeRank()).isEqualTo(4);
    }
}
