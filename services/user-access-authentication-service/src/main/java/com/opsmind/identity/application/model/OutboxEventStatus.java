package com.opsmind.identity.application.model;

/** 08-transaction-and-outbox: a batch dispatcher claims {@code PENDING} rows, retries, and dead-letters exhausted attempts as {@code FAILED}. */
public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
