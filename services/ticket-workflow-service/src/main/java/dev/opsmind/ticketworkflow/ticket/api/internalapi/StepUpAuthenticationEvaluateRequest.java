package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * SPEC-TW-036 api-contract §"Request", extended with {@code stepUpProof}:
 * the step-up evidence to evaluate. Not part of the SPEC's literal
 * shared-template example (see {@code SupportQueueAuthorizationEvaluateRequest}/
 * {@code SensitiveReadAuditPolicyEvaluateRequest}/{@code
 * SecretDetectionEvaluateRequest}), but required for this policy to do
 * anything — this endpoint's caller (a trusted internal service) and the
 * proof's subject (the human/service that completed step-up) are different
 * principals, so the proof cannot be read from the caller's own JWT the way
 * {@code CancelTicketController}/{@code EscalateTicketController} do it.
 * {@code context} is accepted only for shape parity with the shared
 * contract template and is not read.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record StepUpAuthenticationEvaluateRequest(
    @NotBlank
    String ticketId,

    @NotBlank
    String actorId,

    @NotBlank
    String actorType,

    @NotBlank
    String operation,

    @Valid
    StepUpProofPayload stepUpProof,

    @Valid
    Context context
) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StepUpProofPayload(String proofId, String method, String verifiedAt, String expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Context(String supportQueueId) {
    }
}
