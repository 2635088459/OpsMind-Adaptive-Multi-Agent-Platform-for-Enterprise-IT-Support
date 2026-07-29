package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogSubcategory;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketSubcategoryCatalogPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TicketSubcategoryCatalogAdapter implements TicketSubcategoryCatalogPort {

    private static final String SELECT_SQL = """
        SELECT subcategory_id, category_id, code, display_name
        FROM ticket.ticket_subcategories
        WHERE subcategory_id = ? AND active = TRUE
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketSubcategoryCatalogAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CatalogSubcategory> findActiveById(TicketSubcategoryId subcategoryId) {
        List<CatalogSubcategory> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> new CatalogSubcategory(
                subcategoryId,
                TicketCategoryId.of((UUID) rs.getObject("category_id")),
                rs.getString("code"),
                rs.getString("display_name")
            ),
            subcategoryId.value()
        );
        return rows.stream().findFirst();
    }
}
