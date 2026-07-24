package dev.opsmind.ticketworkflow.ticket.outbox;

import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketIntegrationEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ticket.created.v1 payload minimizes PII (BI-102): it must
 * never contain the title, description, raw requesterId, requester email,
 * JWT, or Idempotency-Key, and the requester reference must be a keyed HMAC,
 * not the raw subject or an unsalted hash of it.
 */
@Tag("contract")
class TicketCreatedEventRedactionTest {

    private static final String SECRET = "redaction-test-secret";
    private static final String RAW_REQUESTER_ID = "user-123";

    private final RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(
        new TicketWorkflowProperties(SECRET, new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24)))
    );
    private final TicketIntegrationEventMapper mapper = new TicketIntegrationEventMapper(pseudonymizer);

    @Test
    void shouldExcludeSensitiveAndSecretFieldsFromPayload() {
        TicketCreated event = new TicketCreated(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-2048"), RequesterId.of(RAW_REQUESTER_ID),
            ApplicationCode.HOUSING_PORTAL, TicketSource.PORTAL, 0L, Instant.parse("2026-07-23T16:30:00Z")
        );

        OutboxEventEntry entry = mapper.mapTicketCreated(event, "trace-1", "corr-1", "cmd-1");
        Map<String, Object> payload = entry.payload();

        assertThat(payload).doesNotContainKeys(
            "title", "description", "requesterId", "requesterEmail", "email",
            "jwt", "authorizationHeader", "idempotencyKey", "password", "accessToken", "refreshToken"
        );
        assertThat(payload).containsOnlyKeys(
            "displayId", "requesterIdHash", "applicationCode", "source", "initialStatus", "createdAt"
        );
    }

    @Test
    void shouldPseudonymizeRequesterIdAsKeyedHmacNotRawOrUnsaltedHash() {
        TicketCreated event = new TicketCreated(
            TicketId.of(UUID.randomUUID()), TicketDisplayId.of("INC-2048"), RequesterId.of(RAW_REQUESTER_ID),
            ApplicationCode.HOUSING_PORTAL, TicketSource.PORTAL, 0L, Instant.parse("2026-07-23T16:30:00Z")
        );

        OutboxEventEntry entry = mapper.mapTicketCreated(event, "trace-1", "corr-1", "cmd-1");
        String requesterIdHash = (String) entry.payload().get("requesterIdHash");

        assertThat(requesterIdHash).doesNotContain(RAW_REQUESTER_ID);
        assertThat(requesterIdHash).startsWith("hmac-sha256:");
        assertThat(requesterIdHash).matches("^hmac-sha256:[0-9a-f]{64}$");

        String unsaltedSha256 = sha256Hex(RAW_REQUESTER_ID);
        assertThat(requesterIdHash).doesNotContain(unsaltedSha256);
    }

    private String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
