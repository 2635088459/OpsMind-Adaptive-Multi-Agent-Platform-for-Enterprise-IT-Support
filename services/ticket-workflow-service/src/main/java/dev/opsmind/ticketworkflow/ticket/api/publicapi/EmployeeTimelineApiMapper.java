package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineSortVersion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Maps a {@link TicketTimelineResult} to the Employee wire contract
 * (SPEC-TW-006 §19). Structurally excludes actor IDs, internal actor
 * references, internal reason codes, workflow IDs, and Audit metadata —
 * {@link EmployeeTimelineItemResponse} has no field to carry them through.
 */
@Component
public class EmployeeTimelineApiMapper {

    private static final Set<String> SUPPORT_ACTOR_TYPES = Set.of("IT_SUPPORT", "IT_ADMIN", "IT_MANAGER");

    public EmployeeTimelineResponse toResponse(TicketTimelineResult result) {
        List<EmployeeTimelineItemResponse> items = result.items().stream().map(this::toItem).toList();

        EmployeeTimelineResponse.Page page = new EmployeeTimelineResponse.Page(
            result.limit(), result.hasMore(), result.nextCursor(), result.snapshotAt(), "SNAPSHOT"
        );
        EmployeeTimelineResponse.Sort sort = new EmployeeTimelineResponse.Sort(TicketTimelineSortVersion.CURRENT_VERSION, TicketTimelineSortVersion.FIELDS);

        return new EmployeeTimelineResponse(result.ticketId(), result.displayId(), result.viewType().name(), items, page, sort);
    }

    private EmployeeTimelineItemResponse toItem(TicketTimelineItem item) {
        EmployeeTimelineItemResponse.Actor actor = new EmployeeTimelineItemResponse.Actor(actorType(item.actorType()), displayLabel(item.actorType()));
        EmployeeTimelineItemResponse.Metadata metadata = new EmployeeTimelineItemResponse.Metadata(
            item.fromStatus(), item.toStatus(), item.messageType(), item.relatedVersion()
        );

        return new EmployeeTimelineItemResponse(
            item.itemId(), item.itemType().name(), "PUBLIC", item.occurredAt(), actor, summary(item), item.content(), metadata
        );
    }

    private String actorType(String rawActorType) {
        if (SUPPORT_ACTOR_TYPES.contains(rawActorType)) {
            return "IT_SUPPORT";
        }
        return "SYSTEM".equals(rawActorType) ? "SYSTEM" : "EMPLOYEE";
    }

    private String displayLabel(String rawActorType) {
        if (SUPPORT_ACTOR_TYPES.contains(rawActorType)) {
            return "IT Support";
        }
        return "SYSTEM".equals(rawActorType) ? "System" : "You";
    }

    private String summary(TicketTimelineItem item) {
        return switch (item.itemType()) {
            case TICKET_CREATED -> "Ticket created";
            case STATUS_CHANGED -> item.fromStatus() == null
                ? "Status changed to " + item.toStatus()
                : "Status changed from " + item.fromStatus() + " to " + item.toStatus();
            case PUBLIC_REQUESTER_MESSAGE -> "You added a message";
            case PUBLIC_SUPPORT_MESSAGE -> "IT Support added a message";
            case INTERNAL_SUPPORT_NOTE -> throw new IllegalStateException("internal note must never reach the Employee view");
        };
    }
}
