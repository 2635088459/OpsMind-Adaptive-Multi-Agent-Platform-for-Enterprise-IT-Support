package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueMembershipPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SupportQueueMembershipAdapter implements SupportQueueMembershipPort {

    private static final String EXISTS_SQL = """
        SELECT 1 FROM ticket.support_queue_memberships WHERE agent_id = ? AND support_queue_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public SupportQueueMembershipAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean isMember(String agentId, SupportQueueId supportQueueId) {
        List<Integer> rows = jdbcTemplate.query(EXISTS_SQL, (rs, rowNum) -> 1, agentId, supportQueueId.value());
        return !rows.isEmpty();
    }
}
