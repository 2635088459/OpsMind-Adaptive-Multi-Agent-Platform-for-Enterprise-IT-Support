package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentDirectoryPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportAgentRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SupportAgentDirectoryAdapter implements SupportAgentDirectoryPort {

    private static final String SELECT_SQL = """
        SELECT agent_id, display_name, role, active
        FROM ticket.support_agents
        WHERE agent_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public SupportAgentDirectoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SupportAgentRecord> findById(String agentId) {
        List<SupportAgentRecord> rows = jdbcTemplate.query(
            SELECT_SQL,
            (rs, rowNum) -> new SupportAgentRecord(
                rs.getString("agent_id"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getBoolean("active")
            ),
            agentId
        );
        return rows.stream().findFirst();
    }
}
