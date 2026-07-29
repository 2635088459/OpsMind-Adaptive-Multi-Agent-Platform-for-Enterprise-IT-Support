package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineSortVersion;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps a {@link TicketTimelineResult} to the Support wire contract
 * (SPEC-TW-006 §20). {@code actorRef} is pseudonymized with the same
 * {@link RequesterPseudonymizer} used by Get Ticket's Support view and
 * Support Queue (reused for any actor identity, not only requesters — see
 * SPEC-TW-006's known deviations), so the same actor maps to the same
 * reference across every endpoint.
 */
@Component
public class SupportTimelineApiMapper {

    private final RequesterPseudonymizer pseudonymizer;

    public SupportTimelineApiMapper(RequesterPseudonymizer pseudonymizer) {
        this.pseudonymizer = pseudonymizer;
    }

    public SupportTimelineResponse toResponse(TicketTimelineResult result) {
        List<SupportTimelineItemResponse> items = result.items().stream().map(this::toItem).toList();

        SupportTimelineResponse.Page page = new SupportTimelineResponse.Page(
            result.limit(), result.hasMore(), result.nextCursor(), result.snapshotAt(), "SNAPSHOT"
        );
        SupportTimelineResponse.Sort sort = new SupportTimelineResponse.Sort(TicketTimelineSortVersion.CURRENT_VERSION, TicketTimelineSortVersion.FIELDS);

        return new SupportTimelineResponse(result.ticketId(), result.displayId(), result.viewType().name(), items, page, sort);
    }

    private SupportTimelineItemResponse toItem(TicketTimelineItem item) {
        SupportTimelineItemResponse.Actor actor = new SupportTimelineItemResponse.Actor(
            item.actorType(), displayLabel(item.actorType()), actorRef(item.actorType(), item.actorId())
        );
        SupportTimelineItemResponse.Metadata metadata = new SupportTimelineItemResponse.Metadata(
            item.fromStatus(), item.toStatus(), item.messageType(), item.transitionId(), item.reasonCode(), item.relatedVersion()
        );

        return new SupportTimelineItemResponse(
            item.itemId(), item.itemType().name(), item.visibility(), item.occurredAt(), actor, summary(item), item.content(), metadata
        );
    }

    private String displayLabel(String actorType) {
        return switch (actorType) {
            case "IT_SUPPORT", "IT_ADMIN", "IT_MANAGER" -> "Support agent";
            case "SYSTEM" -> "System";
            default -> "Requester";
        };
    }

    private String actorRef(String actorType, String actorId) {
        if ("SYSTEM".equals(actorType) || actorId == null) {
            return null;
        }
        return pseudonymizer.pseudonymize(RequesterId.of(actorId));
    }

    private String summary(TicketTimelineItem item) {
        return switch (item.itemType()) {
            case TICKET_CREATED -> "Ticket created";
            case STATUS_CHANGED -> item.fromStatus() == null
                ? "Status changed to " + item.toStatus()
                : "Status changed from " + item.fromStatus() + " to " + item.toStatus();
            case PUBLIC_REQUESTER_MESSAGE -> "Requester added a message";
            case PUBLIC_SUPPORT_MESSAGE -> "Support added a message";
            case INTERNAL_SUPPORT_NOTE -> "Internal support note added";
        };
    }
}
