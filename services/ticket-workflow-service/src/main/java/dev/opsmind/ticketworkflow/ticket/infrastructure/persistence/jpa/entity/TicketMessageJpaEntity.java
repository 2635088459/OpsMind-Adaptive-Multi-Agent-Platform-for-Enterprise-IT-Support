package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only: no setters. The application role has no normal UPDATE or
 * DELETE privilege on this table by design (SPEC-TW-004 §10).
 */
@Entity
@Table(schema = "ticket", name = "ticket_messages")
public class TicketMessageJpaEntity {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(name = "visibility", nullable = false)
    private String visibility;

    @Column(name = "author_type", nullable = false)
    private String authorType;

    @Column(name = "author_id", nullable = false)
    private String authorId;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "content_format", nullable = false)
    private String contentFormat;

    @Column(name = "source_command_id")
    private String sourceCommandId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "data_classification", nullable = false)
    private String dataClassification;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "version", nullable = false)
    private long version;

    protected TicketMessageJpaEntity() {
    }

    public TicketMessageJpaEntity(
        UUID messageId,
        UUID ticketId,
        String messageType,
        String visibility,
        String authorType,
        String authorId,
        String content,
        String contentFormat,
        String sourceCommandId,
        String traceId,
        String dataClassification,
        Instant createdAt,
        long version
    ) {
        this.messageId = messageId;
        this.ticketId = ticketId;
        this.messageType = messageType;
        this.visibility = visibility;
        this.authorType = authorType;
        this.authorId = authorId;
        this.content = content;
        this.contentFormat = contentFormat;
        this.sourceCommandId = sourceCommandId;
        this.traceId = traceId;
        this.dataClassification = dataClassification;
        this.createdAt = createdAt;
        this.version = version;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getVisibility() {
        return visibility;
    }

    public String getAuthorType() {
        return authorType;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getContent() {
        return content;
    }

    public String getContentFormat() {
        return contentFormat;
    }

    public String getSourceCommandId() {
        return sourceCommandId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getDataClassification() {
        return dataClassification;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
