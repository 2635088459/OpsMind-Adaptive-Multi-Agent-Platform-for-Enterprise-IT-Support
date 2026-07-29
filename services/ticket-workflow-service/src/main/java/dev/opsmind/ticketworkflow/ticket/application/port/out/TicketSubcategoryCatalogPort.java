package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;

import java.util.Optional;

public interface TicketSubcategoryCatalogPort {

    Optional<CatalogSubcategory> findActiveById(TicketSubcategoryId subcategoryId);
}
