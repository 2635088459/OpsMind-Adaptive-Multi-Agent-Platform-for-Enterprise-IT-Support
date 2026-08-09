package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * SPEC-TW-030 API contract: {@code supportQueueId} is always required (this
 * command routes the ticket to a queue — even a same-queue assignee change
 * must resupply it) and its team is resolved server-side from the queue's
 * own catalog entry, never accepted independently (SPEC-TW-030 domain-rules
 * "must not rewrite resolution evidence" — for the same reason, this
 * endpoint never lets team and queue disagree). {@code assigneeId} is
 * nullable — routing to a queue without a specific owner is valid.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateTicketAssignmentRequest(
    @NotNull
    UUID supportQueueId,

    String assigneeId,

    @NotBlank
    @Size(min = 3, max = 500)
    String reason
) {
}
