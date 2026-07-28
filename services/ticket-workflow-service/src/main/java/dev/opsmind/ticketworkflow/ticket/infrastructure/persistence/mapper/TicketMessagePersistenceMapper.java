package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.mapper;

import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessage;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.TicketMessageJpaEntity;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TicketMessagePersistenceMapper {

    private static final String CONTENT_FORMAT = "PLAIN_TEXT";
    private static final String DATA_CLASSIFICATION = "SENSITIVE";

    public TicketMessageJpaEntity toJpaEntity(TicketMessage message) {
        return new TicketMessageJpaEntity(
            message.id().value(),
            message.ticketId().value(),
            message.messageType().name(),
            message.visibility().name(),
            message.author().authorType(),
            message.author().authorId(),
            message.content().value(),
            CONTENT_FORMAT,
            message.sourceCommandId(),
            currentTraceId(),
            DATA_CLASSIFICATION,
            message.createdAt(),
            message.version()
        );
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
