package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * SPEC-TW-033 api-contract §"Request". {@code context} is optional at the
 * request-shape level (a missing or blank {@code supportQueueId} is a
 * policy-context conflict — {@code 409} — not a {@code 400} validation
 * error, since a queue-unscoped {@code operation}/{@code actorType}
 * combination may legitimately omit it).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SupportQueueAuthorizationEvaluateRequest(
    @NotBlank
    String ticketId,

    @NotBlank
    String actorId,

    @NotBlank
    String actorType,

    @NotBlank
    String operation,

    @Valid
    Context context
) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Context(String supportQueueId) {
    }
}
