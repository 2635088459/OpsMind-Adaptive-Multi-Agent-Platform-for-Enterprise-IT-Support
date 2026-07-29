package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogCategory;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCategoryCatalogPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class TicketCategoryCatalogAdapter implements TicketCategoryCatalogPort {

    private static final String SELECT_SQL = """
        SELECT category_id, code, display_name
        FROM ticket.ticket_categories
        WHERE category_id = ? AND active = TRUE
        """;

    private final JdbcTemplate jdbcTemplate;

    public TicketCategoryCatalogAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CatalogCategory> findActiveById(TicketCategoryId categoryId) {
        List<CatalogCategory> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> new CatalogCategory(
                categoryId,
                rs.getString("code"),
                rs.getString("display_name")
            ),
            categoryId.value()
        );
        return rows.stream().findFirst();
    }
}
