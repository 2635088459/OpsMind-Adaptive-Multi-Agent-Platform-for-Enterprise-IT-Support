package dev.opsmind.ticketworkflow.ticket.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.opsmind.ticketworkflow.support.ListRequesterTicketsFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.RequesterTicketListApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.RequesterTicketListResponse;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketListResult;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-003 §10/§21: the list response excludes Description, requesterId,
 * and internal fields, and conforms exactly to the frozen JSON Schema
 * ({@code additionalProperties: false}) — including schema-required
 * properties that are {@code null} (empty page, {@code nextCursor}).
 */
@Tag("unit")
class ListRequesterTicketsResponseRedactionTest {

    private static final String REQUESTER_ID = "employee-123";

    private final ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final RequesterTicketListApiMapper mapper = new RequesterTicketListApiMapper();

    @Test
    void populatedResponseShouldConformToFrozenSchemaAndExcludeSensitiveFields() throws Exception {
        UUID ticketId = UUID.randomUUID();
        RequesterTicketSummary summary = ListRequesterTicketsFixtures.summary(ticketId, Instant.parse("2026-07-23T16:30:00Z"));
        RequesterTicketListResult result = new RequesterTicketListResult(
            List.of(summary), 20, true, "opaque-cursor-token",
            new TicketListFilters(Set.of(TicketStatus.NEW), Set.of(ApplicationCode.HOUSING_PORTAL), null, null)
        );

        RequesterTicketListResponse response = mapper.toResponse(result);
        String json = objectMapper.writeValueAsString(response);

        assertThat(validate(json)).isEmpty();
        assertThat(json).doesNotContain("description");
        assertThat(json).doesNotContain(REQUESTER_ID);
        assertThat(json).doesNotContain("workflowId");
        assertThat(json).doesNotContain("auditId");
        assertThat(json).doesNotContain("internalNote");
    }

    @Test
    void emptyResponseShouldConformToFrozenSchemaWithExplicitNulls() throws Exception {
        RequesterTicketListResult result = new RequesterTicketListResult(List.of(), 20, false, null, TicketListFilters.none());

        String json = objectMapper.writeValueAsString(mapper.toResponse(result));

        assertThat(validate(json)).isEmpty();
        assertThat(json).contains("\"nextCursor\":null");
    }

    private java.util.Set<ValidationMessage> validate(String json) throws Exception {
        JsonSchema schema = loadSchema("schemas/ticket/requester-ticket-list-response.schema.json");
        JsonNode node = objectMapper.readTree(json);
        return schema.validate(node);
    }

    private JsonSchema loadSchema(String location) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(location)) {
            assertThat(in).as("schema resource on classpath: " + location).isNotNull();
            return schemaFactory.getSchema(in);
        }
    }
}
