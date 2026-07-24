package dev.opsmind.ticketworkflow.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "opsmind.ticket")
public record TicketWorkflowProperties(
    String requesterPseudonymizationSecret,
    Sla sla
) {

    public record Sla(
        @DefaultValue("DEFAULT") String defaultPolicyId,
        @DefaultValue("PT4H") Duration responseDue,
        @DefaultValue("PT24H") Duration resolutionDue
    ) {
    }
}
