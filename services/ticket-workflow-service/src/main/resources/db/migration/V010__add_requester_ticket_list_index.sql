CREATE INDEX ix_tickets_requester_created_ticket
    ON ticket.tickets (requester_id, created_at DESC, ticket_id DESC);
