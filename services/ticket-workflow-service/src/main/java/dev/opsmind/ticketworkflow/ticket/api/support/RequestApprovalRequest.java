package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RequestApprovalRequest(
    @NotBlank
    @Size(max = 128)
    String workflowId,

    @NotBlank
    @Size(max = 128)
    String actionId,

    @NotBlank
    @Size(max = 64)
    String actionType,

    @NotNull
    ApprovalRiskLevel riskLevel,

    @NotEmpty
    Map<String, Object> riskContext,

    @Size(max = 2000)
    String reason
) {
}
