package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractSupportQueueIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-005 §5/§14: this system has no tenant/region column on Ticket
 * (see known_deviations_from_plan), so cross-tenant isolation is exercised
 * at the boundary this codebase actually has — application/queue scope —
 * covering two dimensions: two differently-scoped Support actors never see
 * each other's authorized-only Tickets in the same query, and a cursor
 * issued under one actor's scope is rejected outright under a different
 * (narrower or wider) authorized scope, never silently re-filtered.
 */
@Tag("integration")
class SupportQueueCrossTenantIsolationIT extends AbstractSupportQueueIT {

    @Test
    void twoActorsWithDisjointApplicationScopesShouldEachSeeOnlyTheirOwnTickets() {
        Instant now = Instant.parse("2026-07-25T19:00:00Z");
        UUID housingTicket = seedTicket(DEFAULT_APPLICATION_CODE, "NEW", now);
        UUID vpnTicket = seedVpnTicket(now);

        ResponseEntity<String> housingActorResponse = queryQueue(
            supportToken("support-housing", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of()
        );
        ResponseEntity<String> vpnActorResponse = queryQueue(
            supportToken("support-vpn", Set.of("VPN"), Set.of("TEAM-VPN")), Map.of()
        );

        assertThat(itemTicketIds(bodyAsJson(housingActorResponse))).containsExactly(housingTicket);
        assertThat(itemTicketIds(bodyAsJson(vpnActorResponse))).containsExactly(vpnTicket);
    }

    private UUID seedVpnTicket(Instant now) {
        return seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, "VPN", "NEW", "MEDIUM", "TEAM-VPN", null,
            now.minusSeconds(30), "ACTIVE", now.plusSeconds(86400)
        );
    }

    @Test
    void cursorIssuedUnderOneActorsScopeShouldBeRejectedIfReusedByADifferentlyScopedActor() {
        Instant now = Instant.parse("2026-07-25T19:00:00Z");
        for (int i = 0; i < 3; i++) {
            seedTicket(DEFAULT_APPLICATION_CODE, "NEW", now.minusSeconds(i));
        }

        ResponseEntity<String> firstPage = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("limit", "1")
        );
        String cursor = bodyAsJson(firstPage).get("page").get("nextCursor").asText();

        // A different actor scoped to VPN (instead of HOUSING_PORTAL) reuses the same opaque cursor string.
        ResponseEntity<String> replay = queryQueue(
            supportToken("support-100", Set.of("VPN"), Set.of("TEAM-VPN")), Map.of("cursor", cursor)
        );

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(replay.getBody()).contains("INVALID_CURSOR");
    }
}
