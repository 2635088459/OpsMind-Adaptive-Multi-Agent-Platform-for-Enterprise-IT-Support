package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.Objects;
import java.util.UUID;

public record SupportQueueId(UUID value) {

    public SupportQueueId {
        Objects.requireNonNull(value, "supportQueueId must not be null");
    }

    public static SupportQueueId of(UUID value) {
        return new SupportQueueId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
