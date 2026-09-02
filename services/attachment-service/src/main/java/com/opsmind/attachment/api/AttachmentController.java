package com.opsmind.attachment.api;

import com.opsmind.attachment.application.port.in.FetchAttachmentContentUseCase;
import com.opsmind.attachment.application.port.in.FindAttachmentUseCase;
import com.opsmind.attachment.application.port.in.RequesterContext;
import com.opsmind.attachment.application.port.in.UploadAttachmentCommand;
import com.opsmind.attachment.application.port.in.UploadAttachmentUseCase;
import com.opsmind.attachment.domain.Attachment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * SPEC-EP-010 §13: {@code POST /api/v1/attachments} — the exact contract
 * {@code useUploadAttachment}/{@code uploadAttachment} already builds against
 * ({@code multipart/form-data} with a {@code file} field, returns {@code {ref}}).
 * {@code GET .../content} is this controller's own addition, not named in that
 * spec's text — it exists because SPEC-ARO-039's own multimodal follow-up needs a
 * real way to fetch the bytes back; a "shared" capability with no read-back path
 * would not actually be usable by that caller. Both GET endpoints enforce a real
 * per-owner read authorization (AttachmentService#authorize) — see RequesterContext's
 * own docstring for the exact rule.
 */
@RestController
public class AttachmentController {

    private final UploadAttachmentUseCase uploadAttachmentUseCase;
    private final FindAttachmentUseCase findAttachmentUseCase;
    private final FetchAttachmentContentUseCase fetchAttachmentContentUseCase;

    public AttachmentController(
        UploadAttachmentUseCase uploadAttachmentUseCase, FindAttachmentUseCase findAttachmentUseCase,
        FetchAttachmentContentUseCase fetchAttachmentContentUseCase
    ) {
        this.uploadAttachmentUseCase = uploadAttachmentUseCase;
        this.findAttachmentUseCase = findAttachmentUseCase;
        this.fetchAttachmentContentUseCase = fetchAttachmentContentUseCase;
    }

    @PostMapping(value = "/api/v1/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttachmentUploadResponse> upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read uploaded file", e);
        }
        Attachment attachment = uploadAttachmentUseCase.upload(new UploadAttachmentCommand(
            file.getOriginalFilename(), file.getContentType(), content, jwt.getSubject()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(new AttachmentUploadResponse(attachment.attachmentId().toString()));
    }

    @GetMapping("/api/v1/attachments/{attachmentId}")
    public ResponseEntity<AttachmentResponse> findById(@PathVariable UUID attachmentId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(AttachmentResponse.from(findAttachmentUseCase.findById(attachmentId, toRequesterContext(jwt))));
    }

    @GetMapping("/api/v1/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> fetchContent(@PathVariable UUID attachmentId, @AuthenticationPrincipal Jwt jwt) {
        FetchAttachmentContentUseCase.AttachmentContent content = fetchAttachmentContentUseCase.fetchContent(attachmentId, toRequesterContext(jwt));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.mimeType()))
            .header("Content-Disposition", "inline; filename=\"" + content.filename() + "\"")
            .body(content.content());
    }

    /** Mirrors ticket-workflow-service's own TriageTicketController#resolveActorType exactly: an absent actor_type claim defaults to "EMPLOYEE". */
    private RequesterContext toRequesterContext(Jwt jwt) {
        String actorType = jwt.getClaimAsString("actor_type");
        return new RequesterContext(jwt.getSubject(), actorType != null ? actorType : "EMPLOYEE");
    }
}
