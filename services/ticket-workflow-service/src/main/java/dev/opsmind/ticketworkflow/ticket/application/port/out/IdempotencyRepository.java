package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;

import java.util.UUID;

public interface IdempotencyRepository {

    /**
     * Attempts to reserve {@code (actorScope, idempotencyKey)} as IN_PROGRESS.
     * Returns {@code Reserved} when the caller may proceed (either a fresh
     * reservation or a reclaimed stale one) and other outcomes describing why
     * the caller must not proceed.
     */
    IdempotencyReservationOutcome reserve(IdempotencyReservationRequest request);

    void complete(UUID idempotencyRecordId, IdempotencyCompletion completion);
}
