package com.opsmind.attachment.application.port.in;

import com.opsmind.attachment.domain.Attachment;

public interface UploadAttachmentUseCase {

    Attachment upload(UploadAttachmentCommand command);
}
