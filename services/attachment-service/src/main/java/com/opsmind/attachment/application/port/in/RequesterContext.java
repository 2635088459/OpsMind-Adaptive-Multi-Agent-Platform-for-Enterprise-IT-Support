package com.opsmind.attachment.application.port.in;

/**
 * The caller identity behind a read request ({@code FindAttachmentUseCase}/
 * {@code FetchAttachmentContentUseCase}) — subject/actorType extracted from the real
 * JWT, mirroring ticket-workflow-service's own {@code ActorContext}/{@code actor_type}
 * claim convention exactly ({@code TriageTicketController#resolveActorType}: absent
 * claim defaults to {@code "EMPLOYEE"}). Used to enforce the real per-owner read
 * authorization {@code AttachmentService} applies — see that class's own
 * {@code authorize()} for the rule.
 */
public record RequesterContext(String subject, String actorType) {

    public boolean isEmployee() {
        return "EMPLOYEE".equals(actorType);
    }
}
