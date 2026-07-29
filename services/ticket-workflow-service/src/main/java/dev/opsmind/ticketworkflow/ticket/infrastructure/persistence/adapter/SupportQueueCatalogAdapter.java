package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.CatalogSupportQueue;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueCatalogPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SupportQueueCatalogAdapter implements SupportQueueCatalogPort {

    private static final String SELECT_SQL = """
        SELECT support_queue_id, team_id, display_name
        FROM ticket.support_queues
        WHERE support_queue_id = ? AND active = TRUE
        """;

    private final JdbcTemplate jdbcTemplate;

    public SupportQueueCatalogAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CatalogSupportQueue> findActiveById(SupportQueueId supportQueueId) {
        List<CatalogSupportQueue> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> new CatalogSupportQueue(
                supportQueueId,
                rs.getString("team_id"),
                rs.getString("display_name")
            ),
            supportQueueId.value()
        );
        return rows.stream().findFirst();
    }
}
