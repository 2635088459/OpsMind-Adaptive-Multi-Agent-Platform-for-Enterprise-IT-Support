package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;

import java.time.Instant;

public interface SlaPolicyResolver {

    /**
     * Resolves the local SLA policy applicable to a new ticket. Must not call
     * a remote SLA service; a missing required default policy is a
     * configuration failure.
     */
    ResolvedSlaPolicy resolve(ApplicationCode applicationCode, Instant ticketCreatedAt);
}
