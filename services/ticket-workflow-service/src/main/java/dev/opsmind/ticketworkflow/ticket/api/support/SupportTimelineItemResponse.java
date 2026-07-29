package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Matches {@code schemas/support-timeline-item.schema.json} (SPEC-TW-006
 * §20). Never includes JWTs, credentials, Tool secrets, full Audit
 * records, unapproved identity attributes, or raw event payloads.
 *
 * <p>{@code @JsonInclude(ALWAYS)}: {@code content}, {@code actor.actorRef},
 * and every {@code metadata} field are schema-required but still allowed
 * to be {@code null}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SupportTimelineItemResponse(
    String itemId,
    String itemType,
    String visibility,
    Instant occurredAt,
    Actor actor,
    String summary,
    String content,
    Metadata metadata
) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Actor(String type, String displayLabel, String actorRef) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metadata(
        String fromStatus, String toStatus, String messageType, String transitionId, String reasonCode, Long relatedVersion
    ) {
    }
}
