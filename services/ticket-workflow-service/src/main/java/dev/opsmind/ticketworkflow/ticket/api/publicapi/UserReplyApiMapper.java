package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeResult;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionPolicyDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionPolicy;
import dev.opsmind.ticketworkflow.ticket.domain.exception.SecretContentDetectedException;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * SPEC-TW-035 hardening: a {@link SecretContentDetectedException} from
 * {@link MessageContent} — previously indistinguishable from an ordinary
 * shape violation, and surfaced as a generic {@code 400} — is now recorded
 * through the shared {@link SecretDetectionAuditRecorder} decision ledger
 * and surfaced as {@code 403} via {@link SecretDetectionPolicyDeniedException}.
 */
@Component
public class UserReplyApiMapper {

    private static final String OPERATION = "ticket.message.create";

    private final SecretDetectionPolicy secretDetectionPolicy;
    private final SecretDetectionAuditRecorder secretDetectionAuditRecorder;
    private final TicketTelemetry telemetry;

    public UserReplyApiMapper(
        SecretDetectionPolicy secretDetectionPolicy,
        SecretDetectionAuditRecorder secretDetectionAuditRecorder,
        TicketTelemetry telemetry
    ) {
        this.secretDetectionPolicy = secretDetectionPolicy;
        this.secretDetectionAuditRecorder = secretDetectionAuditRecorder;
        this.telemetry = telemetry;
    }

    public UserReplyAndResumeCommand toCommand(
        TicketId ticketId, UUID requestId, UserReplyRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new UserReplyAndResumeCommand(
            ticketId,
            requestId,
            toMessageContent(request.body(), ticketId, actor, correlationId),
            request.attachmentIds(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public UserReplyResponse toResponse(UserReplyAndResumeResult result) {
        return new UserReplyResponse(
            result.ticketId().value(),
            result.requestId(),
            result.messageId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.answeredAt(),
            result.resumeApplied(),
            result.version()
        );
    }

    private MessageContent toMessageContent(String rawBody, TicketId ticketId, ActorContext actor, String correlationId) {
        try {
            return MessageContent.of(rawBody);
        } catch (SecretContentDetectedException e) {
            telemetry.recordMessageSecretRejected();
            String decisionCode = secretDetectionPolicy.decisionCodeFor(e.category());
            secretDetectionAuditRecorder.recordDenied(
                ticketId.toString(), actor.subject(), actor.actorType(), OPERATION, decisionCode, correlationId, currentTraceId()
            );
            throw new SecretDetectionPolicyDeniedException(decisionCode);
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException(e.getMessage());
        }
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
