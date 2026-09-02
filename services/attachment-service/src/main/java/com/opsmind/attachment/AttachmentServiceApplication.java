package com.opsmind.attachment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The shared attachments capability SPEC-EP-010/011 (09-employee-portal) charter as
 * "a new independent shared capability, not owned by this domain" — this is that
 * capability. Consumed by 2 real callers: 09-employee-portal's own
 * {@code useUploadAttachment}/{@code uploadAttachment} (already built, MSW-mocked
 * against the exact contract this service now implements for real), and
 * 03-agent-runtime-orchestration's own conversational reasoning (SPEC-ARO-039's own
 * multimodal follow-up — a real {@code attachmentRefs} value only becomes fetchable
 * once this service is real).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AttachmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AttachmentServiceApplication.class, args);
    }
}
