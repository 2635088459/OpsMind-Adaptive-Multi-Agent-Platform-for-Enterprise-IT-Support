package dev.opsmind.ticketworkflow.ticket.domain.message;

/**
 * Visibility is fixed per type (SPEC-TW-004 §6) and is never accepted as an
 * independent client input — {@link #visibility()} is the single source of
 * truth, so a message can never end up with a mismatched type/visibility
 * combination.
 */
public enum TicketMessageType {
    PUBLIC_REQUESTER_MESSAGE(MessageVisibility.PUBLIC),
    PUBLIC_SUPPORT_MESSAGE(MessageVisibility.PUBLIC),
    INTERNAL_SUPPORT_NOTE(MessageVisibility.INTERNAL);

    private final MessageVisibility visibility;

    TicketMessageType(MessageVisibility visibility) {
        this.visibility = visibility;
    }

    public MessageVisibility visibility() {
        return visibility;
    }
}
