package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;

/**
 * {@code teamId} is the join key to the existing (previously dormant)
 * {@code ticket.tickets.current_team_id} column and the {@code
 * support_teams} JWT claim SPEC-TW-005 already parses (SPEC-TW-007 is the
 * first command to populate either).
 */
public record CatalogSupportQueue(SupportQueueId supportQueueId, String teamId, String displayName) {
}
