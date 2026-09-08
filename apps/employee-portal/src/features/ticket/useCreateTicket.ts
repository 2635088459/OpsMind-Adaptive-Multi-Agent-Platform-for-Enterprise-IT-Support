import { useMutation } from "@tanstack/react-query";
import { createTicket } from "@/features/ticket/api";
import type { CreateTicketInput, CreateTicketResult } from "@/features/ticket/types";

/**
 * The mutation behind NewTicketForm. No query invalidation to do — this
 * creates a ticket that no list in this app is showing yet; the form itself
 * holds the returned `CreateTicketResult` and hands its `ticketId` to the
 * status panel. `retry: false` inherits from the app QueryClient so a real
 * 4xx (validation, missing scope) surfaces immediately instead of retrying.
 */
export function useCreateTicket() {
  return useMutation<CreateTicketResult, Error, CreateTicketInput>({
    mutationFn: (input) => createTicket(input),
  });
}
