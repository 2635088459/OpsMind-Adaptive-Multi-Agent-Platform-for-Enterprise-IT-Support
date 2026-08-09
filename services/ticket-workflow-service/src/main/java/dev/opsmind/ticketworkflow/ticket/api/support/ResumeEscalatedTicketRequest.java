package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.EscalationResumeReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ResumeEscalatedTicketRequest(
    @NotNull
    EscalationResumeReasonCode resumeReasonCode,

    @NotBlank
    @Size(min = 3, max = 500)
    String resumeReason
) {
}
