package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opsmind.ticketworkflow.support.AbstractSupportQueueIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-005 §17: the projection carries every allowed summary field with correct values. */
@Tag("integration")
class SupportQueueProjectionIT extends AbstractSupportQueueIT {

    @Test
    void shouldProjectAllSummaryFieldsCorrectly() {
        // The query's evaluationTime is real Instant.now() (SystemClockAdapter), not a fixed literal,
        // so resolutionDueAt must be anchored to real "now" to reliably land in ACTIVE (not BREACHED or
        // AT_RISK — the default at-risk window is 4 hours, so this stays well beyond it at 24 hours out).
        Instant now = Instant.now().minusSeconds(600);
        UUID ticketId = seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING", "CRITICAL",
            DEFAULT_TEAM, "agent-200", now, "ACTIVE", now.plusSeconds(86400)
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), java.util.Map.of()
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode item = bodyAsJson(response).get("items").get(0);
        assertThat(item.get("ticketId").asText()).isEqualTo(ticketId.toString());
        assertThat(item.get("applicationCode").asText()).isEqualTo(DEFAULT_APPLICATION_CODE);
        assertThat(item.get("status").asText()).isEqualTo("INVESTIGATING");
        assertThat(item.get("priority").asText()).isEqualTo("P1");
        assertThat(item.get("assignment").get("teamId").asText()).isEqualTo(DEFAULT_TEAM);
        assertThat(item.get("assignment").get("agentId").asText()).isEqualTo("agent-200");
        assertThat(item.get("assignment").get("unassigned").asBoolean()).isFalse();
        assertThat(item.get("sla").get("state").asText()).isEqualTo("ACTIVE");
        assertThat(item.get("sla").get("urgencyRank").asInt()).isEqualTo(2);
        assertThat(item.get("requesterRef").asText()).isNotEqualTo(DEFAULT_REQUESTER);
        assertThat(item.get("version").asLong()).isEqualTo(0);
    }
}
