package dev.opsmind.ticketworkflow;

import dev.opsmind.ticketworkflow.support.InfrastructureContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Tag("component")
class TicketWorkflowApplicationTest implements InfrastructureContainerSupport {

    @Autowired
    private Environment environment;

    @Test
    void shouldLoadSpringApplicationContext() {
        assertThat(environment).isNotNull();
    }

    @Test
    void shouldExposeConfiguredApplicationName() {
        assertThat(environment.getProperty("spring.application.name"))
            .isEqualTo("ticket-workflow-service");
    }
}
