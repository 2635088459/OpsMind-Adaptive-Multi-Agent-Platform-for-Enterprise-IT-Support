package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * SPEC-TW-034 api-contract §"Request". {@code context} is accepted only for
 * shape parity with SPEC-TW-033's shared contract template — this policy's
 * decision does not depend on a Support Queue, so nothing under it is read.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SensitiveReadAuditPolicyEvaluateRequest(
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
