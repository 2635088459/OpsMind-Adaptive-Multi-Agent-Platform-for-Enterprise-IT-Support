package com.opsmind.attachment.application.port.in;

import com.opsmind.attachment.domain.Attachment;

import java.util.UUID;

public interface FindAttachmentUseCase {

    /** Throws AttachmentAccessDeniedException when requester is neither the uploader nor a non-EMPLOYEE (service-identity) actor — see AttachmentService#authorize. */
    Attachment findById(UUID attachmentId, RequesterContext requester);
}
