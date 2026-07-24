package dev.opsmind.ticketworkflow.ticket.application.command;

import java.util.Objects;
import java.util.Set;

public record ActorContext(
    String actorType,
    String subject,
    String clientId,
    Set<String> scopes
) {

    public ActorContext {
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
