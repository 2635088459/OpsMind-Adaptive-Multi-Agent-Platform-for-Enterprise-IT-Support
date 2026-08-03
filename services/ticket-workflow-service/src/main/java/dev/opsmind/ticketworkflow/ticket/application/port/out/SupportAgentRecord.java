package dev.opsmind.ticketworkflow.ticket.application.port.out;

public record SupportAgentRecord(String agentId, String displayName, String role, boolean active) {
}
