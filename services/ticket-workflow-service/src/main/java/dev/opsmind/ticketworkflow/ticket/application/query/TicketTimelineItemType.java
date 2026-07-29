package dev.opsmind.ticketworkflow.ticket.application.query;

/**
 * Timeline item type and its frozen sort rank (SPEC-TW-006 §11, sort
 * version 1). A new item type in a later phase requires a sort-version
 * change, not a silent rank insertion.
 */
public enum TicketTimelineItemType {
    TICKET_CREATED(0),
    STATUS_CHANGED(1),
    PUBLIC_REQUESTER_MESSAGE(2),
    PUBLIC_SUPPORT_MESSAGE(3),
    INTERNAL_SUPPORT_NOTE(4);

    private final int itemTypeRank;

    TicketTimelineItemType(int itemTypeRank) {
        this.itemTypeRank = itemTypeRank;
    }

    public int itemTypeRank() {
        return itemTypeRank;
    }
}
