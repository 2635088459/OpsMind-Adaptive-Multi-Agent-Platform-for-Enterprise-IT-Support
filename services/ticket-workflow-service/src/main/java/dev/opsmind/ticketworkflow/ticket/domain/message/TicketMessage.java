package dev.opsmind.ticketworkflow.ticket.domain.message;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Append-only: no update or delete behavior exists on this aggregate by
 * design (SPEC-TW-004 §10). Corrections use a new Message.
 */
public final class TicketMessage {

    private final TicketMessageId id;
    private final TicketId ticketId;
    private final TicketMessageType messageType;
    private final MessageVisibility visibility;
    private final MessageAuthor author;
    private final MessageContent content;
    private final String sourceCommandId;
    private final Instant createdAt;
    private final long version;
    private final List<TicketMessageAdded> domainEvents = new ArrayList<>();

    private TicketMessage(
        TicketMessageId id,
        TicketId ticketId,
        TicketMessageType messageType,
        MessageAuthor author,
        MessageContent content,
        String sourceCommandId,
        Instant createdAt
    ) {
        this.id = id;
        this.ticketId = ticketId;
        this.messageType = messageType;
        this.visibility = messageType.visibility();
        this.author = author;
        this.content = content;
        this.sourceCommandId = sourceCommandId;
        this.createdAt = createdAt;
        this.version = 0L;
    }

    public static TicketMessage create(
        TicketMessageId id,
        TicketId ticketId,
        TicketMessageType messageType,
        MessageAuthor author,
        MessageContent content,
        String sourceCommandId,
        Instant now
    ) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(messageType, "messageType must not be null");
        Objects.requireNonNull(author, "author must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(now, "now must not be null");

        TicketMessage message = new TicketMessage(id, ticketId, messageType, author, content, sourceCommandId, now);

        message.domainEvents.add(new TicketMessageAdded(
            id, ticketId, messageType, messageType.visibility(), author.authorType(), now
        ));

        return message;
    }

    public List<TicketMessageAdded> pullDomainEvents() {
        List<TicketMessageAdded> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    public TicketMessageId id() {
        return id;
    }

    public TicketId ticketId() {
        return ticketId;
    }

    public TicketMessageType messageType() {
        return messageType;
    }

    public MessageVisibility visibility() {
        return visibility;
    }

    public MessageAuthor author() {
        return author;
    }

    public MessageContent content() {
        return content;
    }

    public String sourceCommandId() {
        return sourceCommandId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
