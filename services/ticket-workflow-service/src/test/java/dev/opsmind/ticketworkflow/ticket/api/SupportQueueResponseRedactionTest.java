package dev.opsmind.ticketworkflow.ticket.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportQueueApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportQueueResponse;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-005 §16/§17: the Support Queue response conforms exactly to the
 * frozen JSON Schema ({@code additionalProperties: false}), including
 * schema-required properties that are {@code null} (empty page, {@code
 * nextCursor}, {@code assignedAgent}).
 */
@Tag("unit")
class SupportQueueResponseRedactionTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final SupportQueueApiMapper mapper = new SupportQueueApiMapper(new RequesterPseudonymizer(new TicketWorkflowProperties(
        "unit-test-secret", "unit-test-cursor-secret",
        new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
    )));

    @Test
    void populatedResponseShouldConformToFrozenSchema() throws Exception {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        SupportQueueResult result = new SupportQueueResult(
            List.of(SupportQueueFixtures.summary(java.util.UUID.randomUUID(), now)),
            25, true, "opaque-cursor-token", now, SupportQueueFixtures.noFilters()
        );

        String json = objectMapper.writeValueAsString(mapper.toResponse(result));

        assertThat(validate(json, "schemas/ticket/support-queue-response.schema.json")).isEmpty();
    }

    @Test
    void emptyResponseShouldConformToFrozenSchemaWithExplicitNulls() throws Exception {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        SupportQueueResult result = new SupportQueueResult(List.of(), 25, false, null, now, SupportQueueFixtures.noFilters());

        String json = objectMapper.writeValueAsString(mapper.toResponse(result));

        assertThat(validate(json, "schemas/ticket/support-queue-response.schema.json")).isEmpty();
        assertThat(json).contains("\"nextCursor\":null");
        assertThat(json).contains("\"assignedAgent\":null");
    }

    @Test
    void summaryWithUnassignedAgentShouldConformToFrozenSchema() throws Exception {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        var summaryWithoutAgent = SupportQueueFixtures.summary(java.util.UUID.randomUUID(), now);
        SupportQueueResult result = new SupportQueueResult(List.of(summaryWithoutAgent), 25, false, null, now, SupportQueueFixtures.noFilters());

        String json = objectMapper.writeValueAsString(mapper.toResponse(result));

        assertThat(validate(json, "schemas/ticket/support-queue-response.schema.json")).isEmpty();
        assertThat(json).contains("\"agentId\":null");
    }

    private Set<ValidationMessage> validate(String json, String schemaLocation) throws Exception {
        JsonSchema schema = loadSchema(schemaLocation);
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
