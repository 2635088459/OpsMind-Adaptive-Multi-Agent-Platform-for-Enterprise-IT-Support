package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;
import java.util.UUID;

public interface VerifiedResolutionRepository {

    /**
     * SPEC-TW-025 domain-rules §"verification evidence is trusted, current,
     * and successful": only a {@code SUCCEEDED} {@code
     * ticket_verification_attempts} row for this exact ticket AND this exact
     * (current) resolution cycle counts — a row that exists but belongs to
     * an old/stale resolution cycle, a different ticket, or never reached
     * {@code SUCCEEDED} is indistinguishable, from the caller's
     * perspective, from no evidence at all (empty).
     */
    Optional<VerifiedVerificationEvidence> findCurrentSucceededEvidence(TicketId ticketId, UUID resolutionCycleId, String verificationEvidenceId);

    VerifiedResolutionUpdateOutcome applyResolution(VerifiedResolutionUpdate update);
}
