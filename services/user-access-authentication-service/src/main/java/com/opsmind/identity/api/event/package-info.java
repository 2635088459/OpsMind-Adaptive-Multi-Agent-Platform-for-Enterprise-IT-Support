/**
 * 06-event-contracts §Consumed Events (Keycloak/admin adapter facts,
 * domain-06 approval/break-glass facts, platform service-identity/tenant
 * lifecycle facts). Deliberately empty in SPEC-UA-001: the real RabbitMQ
 * consumers, {@code processed_events} dedup, and outbox-backed publishers
 * are SPEC-UA-003's job (Identity Outbox Processed Event And Audit
 * Baseline) and SPEC-UA-028's (Identity Lifecycle Events).
 */
package com.opsmind.identity.api.event;
