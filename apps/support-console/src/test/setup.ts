import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { cleanup } from "@testing-library/react";
import { server } from "@/test/mswServer";
import { MockEventSource } from "@/test/mockEventSource";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

// jsdom has no native EventSource (SPEC-EP-014/020) — real bug found live:
// TicketStatusPanel unconditionally opens one, so every OTHER test that
// renders it (not just useTicketStatusStream's own dedicated tests) threw
// "EventSource is not defined" the moment this hook shipped. A safe,
// never-fires-on-its-own default here; tests exercising the stream itself
// still call `vi.stubGlobal("EventSource", MockEventSource)` for fine-grained
// control, same class either way.
(globalThis as { EventSource?: unknown }).EventSource = MockEventSource;

// React Testing Library's own automatic post-test cleanup only self-registers
// when it detects a GLOBAL `afterEach` (Jest provides one; vitest only does
// with `test.globals: true`, which this project's vite.config.ts deliberately
// leaves false — explicit imports over ambient globals). Real bug found live:
// without this, a second render() in the same file leaves the first test's
// DOM mounted alongside it (AuthGate.test.tsx's two "Welcome" headings both
// present at once, failing `findByRole` on ambiguity).
afterEach(() => {
  cleanup();
});
