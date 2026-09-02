package com.opsmind.attachment.infrastructure.persistence;

import com.opsmind.attachment.domain.Attachment;
import com.opsmind.attachment.domain.AttachmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attachments", schema = "attachment")
public class AttachmentJpaEntity {

    @Id
    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false)
    private AttachmentStatus uploadStatus;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AttachmentJpaEntity() {
        // JPA
    }

    public AttachmentJpaEntity(
        UUID attachmentId, String filename, String mimeType, long sizeBytes, String objectKey, String thumbnailUrl,
        AttachmentStatus uploadStatus, String uploadedBy, Instant createdAt, Instant updatedAt
    ) {
        this.attachmentId = attachmentId;
        this.filename = filename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.objectKey = objectKey;
        this.thumbnailUrl = thumbnailUrl;
        this.uploadStatus = uploadStatus;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static AttachmentJpaEntity fromDomain(Attachment attachment) {
        return new AttachmentJpaEntity(
            attachment.attachmentId(), attachment.filename(), attachment.mimeType(), attachment.sizeBytes(),
            attachment.objectKey(), attachment.thumbnailUrl(), attachment.status(), attachment.uploadedBy(),
            attachment.createdAt(), attachment.updatedAt()
        );
    }

    public Attachment toDomain() {
        return new Attachment(attachmentId, filename, mimeType, sizeBytes, objectKey, thumbnailUrl, uploadStatus, uploadedBy, createdAt, updatedAt);
    }
}
