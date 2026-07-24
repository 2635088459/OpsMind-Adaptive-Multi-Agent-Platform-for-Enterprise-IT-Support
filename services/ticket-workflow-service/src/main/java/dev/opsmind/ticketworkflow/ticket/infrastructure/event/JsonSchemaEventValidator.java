package dev.opsmind.ticketworkflow.ticket.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.port.out.EventSchemaValidator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates outgoing integration event payloads against their published JSON
 * Schema (Draft 2020-12) before the Outbox insert, per 06-event-contracts §22
 * and 13-package-and-class-design §28.
 */
@Component
public class JsonSchemaEventValidator implements EventSchemaValidator {

    private static final Map<String, String> SCHEMA_LOCATIONS = Map.of(
        "ticket.created:1", "event-schemas/ticket/published/ticket-created-v1.schema.json"
    );

    private final JsonSchemaFactory schemaFactory;
    private final ObjectMapper objectMapper;
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    public JsonSchemaEventValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    @Override
    public void validate(String eventType, String eventVersion, Map<String, Object> payload) {
        String majorVersion = eventVersion.split("\\.")[0];
        String key = eventType + ":" + majorVersion;
        String location = SCHEMA_LOCATIONS.get(key);
        if (location == null) {
            throw new EventSchemaValidationException(eventType, eventVersion, "no schema registered for " + key);
        }

        JsonSchema schema = schemaCache.computeIfAbsent(location, this::loadSchema);
        JsonNode payloadNode = objectMapper.valueToTree(payload);
        Set<ValidationMessage> errors = schema.validate(payloadNode);
        if (!errors.isEmpty()) {
            throw new EventSchemaValidationException(eventType, eventVersion, errors.toString());
        }
    }

    private JsonSchema loadSchema(String location) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(location)) {
            if (in == null) {
                throw new IllegalStateException("event schema not found on classpath: " + location);
            }
            return schemaFactory.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load event schema: " + location, e);
        }
    }
}
