package dev.opsmind.ticketworkflow.ticket.infrastructure.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ConsumedEventValidator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEC-TW-015: validates inbound events against the canonical envelope
 * schema and a per-event-type payload schema (mirrors {@code
 * JsonSchemaEventValidator}'s outbound-side shape). Kept as a separate
 * validator/port from the outbound one since the two validate data flowing
 * in opposite directions with different failure semantics: an outbound
 * schema failure is this service's own bug (500, roll back); an inbound
 * schema failure is the producer's fault (DLQ, never retried).
 */
@Component
public class JsonSchemaConsumedEventValidator implements ConsumedEventValidator {

    private static final String ENVELOPE_SCHEMA_LOCATION = "event-schemas/common/event-envelope-v1.schema.json";

    private static final Map<String, String> PAYLOAD_SCHEMA_LOCATIONS = Map.of(
        "approval.granted:1", "event-schemas/ticket/consumed/approval-granted-v1.schema.json"
    );

    private final JsonSchemaFactory schemaFactory;
    private final ObjectMapper validationObjectMapper;
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    public JsonSchemaConsumedEventValidator(ObjectMapper objectMapper) {
        this.validationObjectMapper = objectMapper.copy().setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    @Override
    public void validateEnvelope(Map<String, Object> envelope) {
        validateAgainst(ENVELOPE_SCHEMA_LOCATION, envelope, "event-envelope");
    }

    @Override
    public void validatePayload(String eventType, String eventVersion, Map<String, Object> payload) {
        String majorVersion = eventVersion.split("\\.")[0];
        String key = eventType + ":" + majorVersion;
        String location = PAYLOAD_SCHEMA_LOCATIONS.get(key);
        if (location == null) {
            throw new ConsumedEventSchemaInvalidException(eventType, "no consumed schema registered for " + key);
        }
        validateAgainst(location, payload, eventType);
    }

    private void validateAgainst(String location, Map<String, Object> data, String eventTypeForError) {
        JsonSchema schema = schemaCache.computeIfAbsent(location, this::loadSchema);
        JsonNode dataNode = validationObjectMapper.valueToTree(data);
        Set<ValidationMessage> errors = schema.validate(dataNode);
        if (!errors.isEmpty()) {
            throw new ConsumedEventSchemaInvalidException(eventTypeForError, errors.toString());
        }
    }

    private JsonSchema loadSchema(String location) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(location)) {
            if (in == null) {
                throw new IllegalStateException("consumed event schema not found on classpath: " + location);
            }
            return schemaFactory.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load consumed event schema: " + location, e);
        }
    }
}
