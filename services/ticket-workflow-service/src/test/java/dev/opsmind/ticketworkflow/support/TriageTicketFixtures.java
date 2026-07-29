package dev.opsmind.ticketworkflow.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogCategory;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogSubcategory;
import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogSupportQueue;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageGuard;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Fixtures shared by the Triage Ticket (SPEC-TW-007) application-tier unit tests. */
public final class TriageTicketFixtures {

    public static final UUID DEFAULT_TICKET_ID = UUID.fromString("018f0f1e-7b31-7a00-8f42-31f9b25b1a92");
    public static final TicketCategoryId DEFAULT_CATEGORY_ID = TicketCategoryId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    public static final TicketSubcategoryId DEFAULT_SUBCATEGORY_ID = TicketSubcategoryId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    public static final SupportQueueId DEFAULT_SUPPORT_QUEUE_ID = SupportQueueId.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    public static final String DEFAULT_TEAM_ID = "TEAM-HOUSING";
    public static final String TRIAGE_SCOPE = "ticket:triage";
    public static final String DEFAULT_REASON = "VPN access failure affects the requester's scheduled shift.";

    private TriageTicketFixtures() {
    }

    public static ActorContext supportActor(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of(TRIAGE_SCOPE));
    }

    public static ActorContext supportActorWithoutScope(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of());
    }

    public static ActorContext employeeActor(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of(TRIAGE_SCOPE));
    }

    public static TicketTriageGuard guard(UUID ticketId, TicketStatus status, long version) {
        return new TicketTriageGuard(TicketId.of(ticketId), TicketDisplayId.of("INC-1"), status, version);
    }

    public static CatalogCategory activeCategory() {
        return new CatalogCategory(DEFAULT_CATEGORY_ID, "NETWORK", "Network Support");
    }

    public static CatalogSubcategory activeSubcategory() {
        return new CatalogSubcategory(DEFAULT_SUBCATEGORY_ID, DEFAULT_CATEGORY_ID, "VPN", "VPN");
    }

    public static CatalogSubcategory subcategoryWithWrongParent() {
        return new CatalogSubcategory(DEFAULT_SUBCATEGORY_ID, TicketCategoryId.of(UUID.randomUUID()), "VPN", "VPN");
    }

    public static CatalogSupportQueue activeQueue() {
        return new CatalogSupportQueue(DEFAULT_SUPPORT_QUEUE_ID, DEFAULT_TEAM_ID, "Network Support Queue");
    }

    public static TriageTicketCommand command(UUID ticketId, ActorContext actor, Set<String> allowedTeamIds, long expectedVersion, String idempotencyKey) {
        return new TriageTicketCommand(
            TicketId.of(ticketId),
            DEFAULT_CATEGORY_ID,
            DEFAULT_SUBCATEGORY_ID,
            TicketPriority.HIGH,
            DEFAULT_SUPPORT_QUEUE_ID,
            DEFAULT_REASON,
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            "corr-" + UUID.randomUUID(),
            "cmd-" + UUID.randomUUID(),
            Instant.parse("2026-07-29T18:30:00Z")
        );
    }

    public static TriageTicketCommand commandWithPriority(UUID ticketId, ActorContext actor, Set<String> allowedTeamIds, long expectedVersion, String idempotencyKey, TicketPriority priority) {
        return new TriageTicketCommand(
            TicketId.of(ticketId),
            DEFAULT_CATEGORY_ID,
            DEFAULT_SUBCATEGORY_ID,
            priority,
            DEFAULT_SUPPORT_QUEUE_ID,
            DEFAULT_REASON,
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            "corr-" + UUID.randomUUID(),
            "cmd-" + UUID.randomUUID(),
            Instant.parse("2026-07-29T18:30:00Z")
        );
    }
}
