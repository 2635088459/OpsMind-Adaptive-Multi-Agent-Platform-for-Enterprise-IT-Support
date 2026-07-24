package dev.opsmind.ticketworkflow.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;

/**
 * Shared fixture for Create Ticket integration tests: full Spring context on
 * a random port, real PostgreSQL and RabbitMQ Testcontainers, and a real
 * signed JWT (via {@link TestSecurityConfiguration}) so requests pass
 * through the actual Spring Security filter chain. Not itself a test class
 * (abstract, no {@code @Test} methods), so Surefire/Failsafe skip it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
@Testcontainers
public abstract class AbstractCreateTicketIT implements InfrastructureContainerSupport {

    protected static final String CREATE_TICKETS_PATH = "/api/v1/tickets";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected String bearerTokenFor(String subject) {
        return TestJwtSupport.mintToken(subject, TicketFixtures.DEFAULT_CLIENT_ID, Set.of(TicketFixtures.CREATE_SCOPE));
    }

    protected String bearerTokenWithoutCreateScope(String subject) {
        return TestJwtSupport.mintToken(subject, TicketFixtures.DEFAULT_CLIENT_ID, Set.of());
    }

    protected ResponseEntity<String> createTicket(String subject, String idempotencyKey, String requestBody) {
        return createTicketWithToken(bearerTokenFor(subject), idempotencyKey, requestBody);
    }

    protected ResponseEntity<String> createTicketWithToken(String bearerToken, String idempotencyKey, String requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        return restTemplate.exchange(CREATE_TICKETS_PATH, HttpMethod.POST, entity, String.class);
    }

    protected String validRequestBody() {
        return validRequestBody("Cannot sign in to Housing Portal", "Duo keeps asking me to enroll again.");
    }

    protected String validRequestBody(String title, String description) {
        return """
            {"title":"%s","description":"%s","applicationCode":"HOUSING_PORTAL","source":"PORTAL"}
            """.formatted(title, description).strip();
    }

    protected String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    protected int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
