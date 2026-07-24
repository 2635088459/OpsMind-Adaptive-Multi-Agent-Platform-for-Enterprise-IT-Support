package dev.opsmind.ticketworkflow.ticket.infrastructure.sla;

import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ResolvedSlaPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SlaPolicyResolver;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Resolves the SLA policy from local application configuration only, per
 * SPEC-TW-001 §12: no remote SLA service is called inside the Create Ticket
 * transaction. Phase 01 applies a single default policy regardless of
 * {@link ApplicationCode}; per-application-code policies are a later phase.
 */
@Component
public class LocalConfigSlaPolicyResolver implements SlaPolicyResolver {

    private final TicketWorkflowProperties properties;

    public LocalConfigSlaPolicyResolver(TicketWorkflowProperties properties) {
        this.properties = properties;
    }

    @Override
    public ResolvedSlaPolicy resolve(ApplicationCode applicationCode, Instant ticketCreatedAt) {
        TicketWorkflowProperties.Sla sla = properties.sla();
        if (sla == null) {
            throw new IllegalStateException("opsmind.ticket.sla must be configured with a default policy");
        }
        return new ResolvedSlaPolicy(
            sla.defaultPolicyId(),
            ticketCreatedAt.plus(sla.responseDue()),
            ticketCreatedAt.plus(sla.resolutionDue())
        );
    }
}
