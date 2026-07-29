package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;

public record CatalogSubcategory(TicketSubcategoryId subcategoryId, TicketCategoryId categoryId, String code, String displayName) {
}
