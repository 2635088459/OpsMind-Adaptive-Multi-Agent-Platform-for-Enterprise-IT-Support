package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared response envelope for assign/reassign/unassign (API contract
 * §5). {@code @JsonInclude(ALWAYS)} on the nullable fields: the app-wide
 * {@code ObjectMapper} default is {@code non_null} (see SPEC-TW-007's
 * {@code JsonSchemaEventValidator} bugfix), which would otherwise omit
 * {@code assignee}/{@code assignedAt} entirely on unassign instead of
 * rendering the literal {@code null} the API contract requires.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TicketAssignmentResponse(
    UUID ticketId,
    String status,
    @JsonInclude(JsonInclude.Include.ALWAYS)
    Assignee assignee,
    @JsonInclude(JsonInclude.Include.ALWAYS)
    Instant assignedAt,
    long version
) {

    /** {@code null} for a successful unassign (API contract §5: "assignee and assignedAt are null"). */
    public record Assignee(String id, String displayName) {
    }
}
