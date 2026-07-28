package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;

import java.util.List;

/**
 * Resolves the trusted Support Queue authorization scope from raw claim
 * values (SPEC-TW-005 §5: "a local authorization projection, or an
 * approved local policy adapter"). The controller extracts the raw claim
 * lists from the verified JWT — never the JWT itself, keeping this port
 * free of any Spring Security type — so this adapter can later be
 * replaced by a database-backed or remote-cached policy source without
 * touching the controller.
 */
public interface SupportQueueScopePort {

    SupportQueueScope resolve(List<String> rawApplicationCodes, List<String> rawTeamIds);
}
