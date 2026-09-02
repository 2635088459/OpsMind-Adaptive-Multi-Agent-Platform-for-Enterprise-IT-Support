package com.opsmind.attachment.application.port.out;

import com.opsmind.attachment.domain.Attachment;

import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository {

    Attachment save(Attachment attachment);

    Optional<Attachment> findById(UUID attachmentId);
}
