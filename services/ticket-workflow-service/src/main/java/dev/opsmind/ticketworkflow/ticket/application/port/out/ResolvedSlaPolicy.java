package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.time.Instant;

public record ResolvedSlaPolicy(
    String policyId,
    Instant responseDueAt,
    Instant resolutionDueAt
) {
}
