package dev.opsmind.ticketworkflow.infrastructure;

import dev.opsmind.ticketworkflow.support.InfrastructureContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class RabbitMqConnectivityIT implements InfrastructureContainerSupport {

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    void shouldEstablishAmqpConnection() {
        try (Connection connection = connectionFactory.createConnection()) {
            assertThat(connection.isOpen()).isTrue();
        }
    }
}
