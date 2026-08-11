package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.util.Optional;

public interface ReplayEventGuardPort {

    /** Resolves the ticket that the original event identified by {@code sourceReference} (an outbox {@code event_id}) belongs to. */
    Optional<ReplayEventGuard> loadOriginalEvent(String sourceReference);
}
