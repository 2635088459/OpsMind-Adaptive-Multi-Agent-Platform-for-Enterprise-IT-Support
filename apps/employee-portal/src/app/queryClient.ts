import { QueryClient } from "@tanstack/react-query";

/**
 * Shared across the app per the frozen technology-baseline (TanStack Query).
 * Not yet used by SPEC-EP-001 itself (a plain fetch inside a Zustand action
 * is enough for one boolean-ish session check) — instantiated now so later
 * specs' real data queries (tickets, messages) have a provider already in
 * place rather than retrofitting one under every future feature.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});
