package com.opsmind.identity.application.port.out;

/**
 * 13-package-and-class-design §Output Ports; 08-transaction-and-outbox: the
 * real outbox table/dispatcher is SPEC-UA-003's job (Identity Outbox
 * Processed Event And Audit Baseline). This port exists now so application
 * services can depend on the seam from day one; the SPEC-UA-001-scoped
 * adapter only logs, matching {@code infrastructure.messaging}'s own
 * javadoc.
 */
public interface EventPublisherPort {

    void publish(String eventType, String aggregateId, String payloadJson);
}
