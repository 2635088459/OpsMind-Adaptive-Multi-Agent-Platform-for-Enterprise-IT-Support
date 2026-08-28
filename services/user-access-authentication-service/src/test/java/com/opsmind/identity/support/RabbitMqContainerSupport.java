package com.opsmind.identity.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

/** SPEC-UA-028. Mirrors policy-approval-governance-service's own identically-named interface. */
public interface RabbitMqContainerSupport {

    @Container
    @ServiceConnection
    RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4.3.4-management");
}
