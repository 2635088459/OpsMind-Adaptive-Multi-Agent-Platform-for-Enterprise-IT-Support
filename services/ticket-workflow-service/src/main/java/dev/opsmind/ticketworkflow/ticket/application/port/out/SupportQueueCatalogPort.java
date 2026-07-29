package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;

import java.util.Optional;

public interface SupportQueueCatalogPort {

    Optional<CatalogSupportQueue> findActiveById(SupportQueueId supportQueueId);
}
