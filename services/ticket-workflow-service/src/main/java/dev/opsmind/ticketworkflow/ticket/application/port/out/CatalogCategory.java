package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;

public record CatalogCategory(TicketCategoryId categoryId, String code, String displayName) {
}
