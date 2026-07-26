package dev.opsmind.ticketworkflow.ticket.application.query;

/**
 * Outcome of {@code If-None-Match} evaluation (SPEC-TW-002 §15). Authorization
 * and resource resolution always run first, so by the time either variant is
 * produced the actor is already known to be allowed to see the resource;
 * {@code NotModified} never leaks more than the version already implied by
 * the caller's own {@code If-None-Match} header.
 */
public sealed interface ConditionalGetResult permits ConditionalGetResult.Found, ConditionalGetResult.NotModified {

    record Found(GetTicketResult result) implements ConditionalGetResult {
    }

    record NotModified(long version) implements ConditionalGetResult {
    }
}
