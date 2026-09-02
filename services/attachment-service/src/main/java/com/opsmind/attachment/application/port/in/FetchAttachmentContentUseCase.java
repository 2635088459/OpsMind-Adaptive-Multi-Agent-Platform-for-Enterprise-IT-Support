package com.opsmind.attachment.application.port.in;

import java.util.UUID;

/**
 * SPEC-ARO-039's own multimodal follow-up is this method's real, primary consumer:
 * agent-runtime-service fetches the real bytes here before including them in an LLM
 * prompt, authenticating via its own SPEC-ARO-043 outbound service identity (a
 * non-EMPLOYEE actor_type) — see RequesterContext's own docstring for why that
 * identity, not the employee's, satisfies the real per-owner read authorization here.
 */
public interface FetchAttachmentContentUseCase {

    /** Throws AttachmentAccessDeniedException when requester is neither the uploader nor a non-EMPLOYEE (service-identity) actor — see AttachmentService#authorize. */
    AttachmentContent fetchContent(UUID attachmentId, RequesterContext requester);

    record AttachmentContent(byte[] content, String mimeType, String filename) {
    }
}
