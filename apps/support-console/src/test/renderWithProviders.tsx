import type { ReactElement } from "react";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

/**
 * Any component reaching a `useMutation`/`useQuery` hook needs a real
 * `QueryClientProvider` ancestor or TanStack Query throws immediately — a
 * fresh `QueryClient` per render, `retry: false` so a test asserting an
 * error path doesn't sit through real retry backoff delays.
 *
 * Also wraps in a `MemoryRouter`: the app is router-based now, so any
 * component may render a `<Link>` / call `useParams`. Tests that need a
 * *data* router (loaders, nested routes) build their own `createMemoryRouter`
 * + `RouterProvider` instead of using this helper.
 */
export function renderWithProviders(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}
