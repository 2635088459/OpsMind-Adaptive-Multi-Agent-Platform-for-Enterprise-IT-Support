package dev.opsmind.ticketworkflow.infrastructure;

import dev.opsmind.ticketworkflow.support.InfrastructureContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class ActuatorHealthIT implements InfrastructureContainerSupport {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldExposeHealthWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void shouldExposeLivenessAndReadinessWithoutAuthentication() {
        ResponseEntity<String> liveness = restTemplate.getForEntity("/actuator/health/liveness", String.class);
        ResponseEntity<String> readiness = restTemplate.getForEntity("/actuator/health/readiness", String.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldExposeInfoWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldDenyPrometheusScrapeWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
