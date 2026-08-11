package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.util.Optional;

public interface DataIntegrityRepairGuardPort {

    /** Resolves the ticket that the reconciliation case identified by {@code sourceReference} (a SPEC-TW-037 case id) belongs to. */
    Optional<DataIntegrityRepairGuard> loadTargetCase(String sourceReference);
}
