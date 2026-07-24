package dev.opsmind.ticketworkflow.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

public interface RabbitMqContainerSupport {

    @Container
    @ServiceConnection
    RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4.3.4-management");
}
