package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import dev.opsmind.ticketworkflow.ticket.infrastructure.event.JsonSchemaEventValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-007 §5/event-contract: a real HTTP triage call, then the actual
 * committed {@code ticket.outbox_events} row's JSONB payload is read back
 * and validated against the published {@code ticket.triaged.v1} schema —
 * end-to-end proof that the mapper/adapter/schema agree in a real
 * transaction, not just in the unit-level contract test.
 */
@Tag("integration")
class TicketTriagedEventPublishedIT extends AbstractTriageTicketIT {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaEventValidator validator = new JsonSchemaEventValidator(new ObjectMapper());

    @Test
    void shouldPublishAnOutboxEventWhosePayloadMatchesTheApprovedSchemaAndFields() throws Exception {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID subcategoryId = seedSubcategory(categoryId, true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, subcategoryId, "HIGH", queueId)
        );
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);

        Map<String, Object> outboxRow = jdbcTemplate.queryForMap(
            "SELECT event_type, event_version, routing_key, payload::text AS payload_json FROM ticket.outbox_events WHERE ticket_id = ?",
            ticketId
        );

        String eventType = (String) outboxRow.get("event_type");
        String eventVersion = (String) outboxRow.get("event_version");
        JsonNode payloadNode = objectMapper.readTree((String) outboxRow.get("payload_json"));

        assertThat(eventType).isEqualTo("ticket.triaged");
        assertThat(eventVersion).isEqualTo("1.0");
        assertThat(outboxRow.get("routing_key")).isEqualTo("ticket.triaged.v1");

        assertThat(payloadNode.get("ticketId").asText()).isEqualTo(ticketId.toString());
        assertThat(payloadNode.get("fromStatus").asText()).isEqualTo("NEW");
        assertThat(payloadNode.get("toStatus").asText()).isEqualTo("TRIAGED");
        assertThat(payloadNode.get("categoryId").asText()).isEqualTo(categoryId.toString());
        assertThat(payloadNode.get("subcategoryId").asText()).isEqualTo(subcategoryId.toString());
        assertThat(payloadNode.get("priority").asText()).isEqualTo("HIGH");
        assertThat(payloadNode.get("supportQueueId").asText()).isEqualTo(queueId.toString());
        assertThat(payloadNode.get("ticketVersion").asLong()).isEqualTo(1L);
        assertThat(payloadNode.has("reason")).isFalse();

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = objectMapper.convertValue(payloadNode, HashMap.class);
        validator.validate(eventType, eventVersion, payloadMap);
    }
}
