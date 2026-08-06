package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

public interface TicketVerificationStartRepository {

    /** SPEC-TW-022 domain-rules §"tool result belongs to the current workflow/cycle/action": only a {@code COMPLETED} row for this exact ticket proves the tool result is real and belongs here. */
    Optional<CompletedToolResultReference> findCompletedToolResult(TicketId ticketId, String toolResultId);

    /** SPEC-TW-022 domain-rules §"no two active verification attempts for the same tool result" — checked up front for a clean conflict response; {@code uq_verification_one_active_tool_result} is the authoritative race guard. */
    boolean hasActiveAttempt(String toolResultId);

    /** SPEC-TW-022 domain-rules §"attemptNumber is monotonic", scoped per {@code toolResultId}. */
    int nextAttemptNumber(String toolResultId);

    TicketVerificationStartUpdateOutcome startVerification(TicketVerificationStartUpdate update);
}
