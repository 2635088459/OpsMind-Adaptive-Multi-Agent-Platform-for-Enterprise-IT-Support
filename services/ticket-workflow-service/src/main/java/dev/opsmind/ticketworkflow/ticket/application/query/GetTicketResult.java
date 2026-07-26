package dev.opsmind.ticketworkflow.ticket.application.query;

/**
 * Actor-specific projection returned by {@link
 * dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase}.
 * Kept as a sealed type (rather than one DTO with nullable fields) so the
 * API layer is forced to handle each view explicitly and can never render
 * the wrong projection's fields (SPEC-TW-002 §12, §13).
 */
public sealed interface GetTicketResult permits GetTicketResult.Employee, GetTicketResult.Support {

    long version();

    record Employee(EmployeeTicketProjection projection) implements GetTicketResult {
        @Override
        public long version() {
            return projection.version();
        }
    }

    record Support(SupportTicketProjection projection) implements GetTicketResult {
        @Override
        public long version() {
            return projection.version();
        }
    }
}
