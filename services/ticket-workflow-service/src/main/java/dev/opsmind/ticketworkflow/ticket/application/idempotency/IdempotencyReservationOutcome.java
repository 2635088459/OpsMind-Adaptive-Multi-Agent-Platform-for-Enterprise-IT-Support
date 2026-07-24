package dev.opsmind.ticketworkflow.ticket.application.idempotency;

public sealed interface IdempotencyReservationOutcome
    permits IdempotencyReservationOutcome.Reserved,
            IdempotencyReservationOutcome.Replayed,
            IdempotencyReservationOutcome.KeyReused,
            IdempotencyReservationOutcome.RequestInProgress {

    record Reserved(java.util.UUID idempotencyRecordId) implements IdempotencyReservationOutcome {
    }

    record Replayed(int responseStatus, String responseBodyJson) implements IdempotencyReservationOutcome {
    }

    record KeyReused() implements IdempotencyReservationOutcome {
    }

    record RequestInProgress() implements IdempotencyReservationOutcome {
    }
}
