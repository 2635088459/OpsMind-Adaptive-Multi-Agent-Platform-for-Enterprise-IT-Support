package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;

import java.util.Optional;

/**
 * A missing row and an inactive row are both reported as {@link
 * Optional#empty()} — AC-03 treats "does not exist" and "is inactive" as
 * the same 422 outcome, so the caller never needs to distinguish them.
 */
public interface TicketCategoryCatalogPort {

    Optional<CatalogCategory> findActiveById(TicketCategoryId categoryId);
}
