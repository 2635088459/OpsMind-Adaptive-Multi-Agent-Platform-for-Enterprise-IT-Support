package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;

public interface SupportQueueMembershipPort {

    boolean isMember(String agentId, SupportQueueId supportQueueId);
}
