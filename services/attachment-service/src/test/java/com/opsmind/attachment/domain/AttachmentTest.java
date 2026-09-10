package com.opsmind.attachment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain unit coverage for {@link Attachment} — the multimodal path's own entity.
 * {@code createReady} is the one factory a real row is ever built through
 * (V001__create_attachments_table.sql: READY is the only persisted status today).
 */
class AttachmentTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

    @Test
    void createReady_builds_a_READY_row_with_no_thumbnail_and_equal_timestamps() {
        UUID id = UUID.randomUUID();

        Attachment attachment = Attachment.createReady(
            id, "screenshot.png", "image/png", 2048L, "attachments/2026/09/" + id, "employee-7", NOW
        );

        assertThat(attachment.attachmentId()).isEqualTo(id);
        assertThat(attachment.filename()).isEqualTo("screenshot.png");
        assertThat(attachment.mimeType()).isEqualTo("image/png");
        assertThat(attachment.sizeBytes()).isEqualTo(2048L);
        assertThat(attachment.objectKey()).isEqualTo("attachments/2026/09/" + id);
        assertThat(attachment.uploadedBy()).isEqualTo("employee-7");
        assertThat(attachment.status()).isEqualTo(AttachmentStatus.READY);
        assertThat(attachment.thumbnailUrl()).isNull();
        assertThat(attachment.createdAt()).isEqualTo(NOW);
        assertThat(attachment.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void the_object_key_is_carried_verbatim_and_never_derived_from_the_filename() {
        // The API surfaces only attachmentId as the opaque ref; objectKey stays internal
        // and is whatever the caller (AttachmentService) computed — a hostile filename
        // must not be able to steer it.
        UUID id = UUID.randomUUID();
        String safeKey = "attachments/" + id;

        Attachment attachment = Attachment.createReady(
            id, "../../etc/passwd", "application/pdf", 10L, safeKey, "employee-1", NOW
        );

        assertThat(attachment.objectKey()).isEqualTo(safeKey);
        assertThat(attachment.filename()).isEqualTo("../../etc/passwd");  // stored for display only
    }
}
