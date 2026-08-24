package com.opsmind.policygovernance.application.exception;

public class OutboxEventNotFoundException extends RuntimeException {

    public OutboxEventNotFoundException(String outboxId) {
        super("outbox event " + outboxId + " was not found");
    }
}
