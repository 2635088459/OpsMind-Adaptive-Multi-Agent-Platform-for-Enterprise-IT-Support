import { useQuery } from "@tanstack/react-query";
import { getSupportTicket } from "@/features/ticket/api";

/** The single-ticket Support detail behind TicketDetailPage — real fetch, not a queue-cache lookup. */
export function useSupportTicket(ticketId: string) {
  return useQuery({
    queryKey: ["support-ticket", ticketId],
    queryFn: () => getSupportTicket(ticketId),
    enabled: ticketId.length > 0,
  });
}
