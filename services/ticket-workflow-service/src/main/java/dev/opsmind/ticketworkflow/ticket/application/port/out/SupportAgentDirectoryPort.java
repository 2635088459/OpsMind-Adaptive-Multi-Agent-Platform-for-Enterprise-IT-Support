package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.util.Optional;

/**
 * A local substitute for "identity ownership may live in another
 * service" (SPEC-TW-008 persistence §1): no external identity/directory
 * service exists anywhere in this codebase, so a minimal local agent
 * directory (existence + active flag + role) stands in for it. A missing
 * row is reported as {@link Optional#empty()} (→ {@code
 * ASSIGNEE_NOT_FOUND}); an existing-but-inactive/non-support row is
 * returned so the caller can distinguish {@code ASSIGNEE_INACTIVE} from
 * {@code ASSIGNEE_NOT_SUPPORT_AGENT}.
 */
public interface SupportAgentDirectoryPort {

    Optional<SupportAgentRecord> findById(String agentId);
}
