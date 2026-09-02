package com.opsmind.attachment.api;

import com.opsmind.attachment.application.exception.AttachmentAccessDeniedException;
import com.opsmind.attachment.application.exception.AttachmentNotFoundException;
import com.opsmind.attachment.application.exception.AttachmentTooLargeException;
import com.opsmind.attachment.application.exception.UnsupportedMimeTypeException;
import com.opsmind.attachment.application.port.in.FetchAttachmentContentUseCase;
import com.opsmind.attachment.application.port.in.FindAttachmentUseCase;
import com.opsmind.attachment.application.port.in.UploadAttachmentUseCase;
import com.opsmind.attachment.config.SecurityConfig;
import com.opsmind.attachment.domain.Attachment;
import com.opsmind.attachment.domain.AttachmentStatus;
import com.opsmind.attachment.platform.error.GlobalRestExceptionHandler;
import com.opsmind.attachment.support.TestSecurityConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-EP-010 §13: the real HTTP contract useUploadAttachment/uploadAttachment already build against, now backed for real. */
@WebMvcTest(AttachmentController.class)
@Import({SecurityConfig.class, GlobalRestExceptionHandler.class, TestSecurityConfig.class})
@Tag("component")
class AttachmentControllerTest {

    private static final UUID ATTACHMENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UploadAttachmentUseCase uploadAttachmentUseCase;

    @MockitoBean
    private FindAttachmentUseCase findAttachmentUseCase;

    @MockitoBean
    private FetchAttachmentContentUseCase fetchAttachmentContentUseCase;

    private Attachment readyAttachment() {
        return new Attachment(
            ATTACHMENT_ID, "screenshot.png", "image/png", 3, "att/screenshot.png", null,
            AttachmentStatus.READY, "employee-1", Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z")
        );
    }

    @Test
    void uploadReturnsTheRealRefContractExactly() throws Exception {
        when(uploadAttachmentUseCase.upload(any())).thenReturn(readyAttachment());
        MockMultipartFile file = new MockMultipartFile("file", "screenshot.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/attachments").file(file).with(jwt()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ref").value(ATTACHMENT_ID.toString()));
    }

    @Test
    void uploadWithoutAuthenticationIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "screenshot.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/attachments").file(file))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnsupportedMimeTypeComesBackAsARealUnprocessableEntityWithADistinctCode() throws Exception {
        when(uploadAttachmentUseCase.upload(any())).thenThrow(new UnsupportedMimeTypeException("application/x-msdownload"));
        MockMultipartFile file = new MockMultipartFile("file", "payload.exe", "application/x-msdownload", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/attachments").file(file).with(jwt()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MIME_TYPE"));
    }

    @Test
    void aTooLargeFileComesBackWithADistinctCode() throws Exception {
        when(uploadAttachmentUseCase.upload(any())).thenThrow(new AttachmentTooLargeException(999, 100));
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/attachments").file(file).with(jwt()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("ATTACHMENT_TOO_LARGE"));
    }

    @Test
    void findByIdReturnsTheRealDomainModelShape() throws Exception {
        when(findAttachmentUseCase.findById(eq(ATTACHMENT_ID), any())).thenReturn(readyAttachment());

        mockMvc.perform(get("/api/v1/attachments/{id}", ATTACHMENT_ID).with(jwt().jwt(jwt -> jwt.subject("employee-1"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attachmentId").value(ATTACHMENT_ID.toString()))
            .andExpect(jsonPath("$.filename").value("screenshot.png"))
            .andExpect(jsonPath("$.mimeType").value("image/png"))
            .andExpect(jsonPath("$.uploadStatus").value("ready"));
    }

    @Test
    void findByIdForAnUnknownAttachmentIsARealNotFound() throws Exception {
        when(findAttachmentUseCase.findById(eq(ATTACHMENT_ID), any())).thenThrow(new AttachmentNotFoundException(ATTACHMENT_ID));

        mockMvc.perform(get("/api/v1/attachments/{id}", ATTACHMENT_ID).with(jwt()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void findByIdForSomeoneElsesAttachmentIsARealForbidden() throws Exception {
        when(findAttachmentUseCase.findById(eq(ATTACHMENT_ID), any())).thenThrow(new AttachmentAccessDeniedException(ATTACHMENT_ID));

        mockMvc.perform(get("/api/v1/attachments/{id}", ATTACHMENT_ID).with(jwt().jwt(jwt -> jwt.subject("employee-2"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("ATTACHMENT_ACCESS_DENIED"));
    }

    @Test
    void fetchContentReturnsTheRealBytesWithTheRealContentType() throws Exception {
        when(fetchAttachmentContentUseCase.fetchContent(eq(ATTACHMENT_ID), any()))
            .thenReturn(new FetchAttachmentContentUseCase.AttachmentContent(new byte[]{9, 8, 7}, "image/png", "screenshot.png"));

        mockMvc.perform(get("/api/v1/attachments/{id}/content", ATTACHMENT_ID).with(jwt().jwt(jwt -> jwt.subject("employee-1"))))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(new byte[]{9, 8, 7}));
    }

    @Test
    void fetchContentAuthenticatesTheRequesterFromTheRealJwtActorTypeClaim() throws Exception {
        when(fetchAttachmentContentUseCase.fetchContent(eq(ATTACHMENT_ID), argThat(r -> r.subject().equals("agent-runtime-service") && !r.isEmployee())))
            .thenReturn(new FetchAttachmentContentUseCase.AttachmentContent(new byte[]{9, 8, 7}, "image/png", "screenshot.png"));

        // A non-EMPLOYEE service identity (SPEC-ARO-043's own outbound token, e.g.
        // agent-runtime-service's own HttpAttachmentClient) can read any attachment —
        // mirrors ticket-workflow-service's own TriageTicketController "any
        // non-EMPLOYEE actor_type" rule.
        mockMvc.perform(get("/api/v1/attachments/{id}/content", ATTACHMENT_ID)
                .with(jwt().jwt(jwt -> jwt.subject("agent-runtime-service").claim("actor_type", "SUPPORT_AGENT"))))
            .andExpect(status().isOk());
    }
}
