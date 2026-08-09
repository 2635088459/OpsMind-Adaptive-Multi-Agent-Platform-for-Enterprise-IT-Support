package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SPEC-TW-035 api-contract §"Request", extended with {@code content}: the
 * free text to evaluate for secret-like patterns. {@code content} is not
 * part of the SPEC's literal shared-template example (see {@code
 * SupportQueueAuthorizationEvaluateRequest}/{@code
 * SensitiveReadAuditPolicyEvaluateRequest}), but is required for this
 * policy to do anything — a "secret detection" evaluation with no text to
 * scan would be vacuous. {@code context} is accepted only for shape parity
 * with the shared contract template and is not read.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SecretDetectionEvaluateRequest(
    @NotBlank
    String ticketId,

    @NotBlank
    String actorId,

    @NotBlank
    String actorType,

    @NotBlank
    String operation,

    @Size(max = 20000)
    String content,

    @Valid
    Context context
) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Context(String supportQueueId) {
    }
}
