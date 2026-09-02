import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { BFF_BASE_URL } from "@/lib/env";
import { TraceWaterfall } from "@/features/trace/TraceWaterfall";

const TRACE_URL = `${BFF_BASE_URL}/api/v1/observability/traces/trace-1`;

function traceResponse(overrides: Record<string, unknown> = {}) {
  return {
    traceId: "trace-1",
    spans: [
      { spanId: "root", parentSpanId: null, name: "handle-ticket", serviceName: "ticket-workflow-service", tenant: "ticket-workflow", kind: "SPAN_KIND_SERVER", statusCode: "STATUS_CODE_OK", startOffsetMs: 0, durationMs: 30 },
      { spanId: "child", parentSpanId: "root", name: "escalate", serviceName: "agent-runtime-service", tenant: "agent-runtime", kind: "SPAN_KIND_CLIENT", statusCode: "STATUS_CODE_OK", startOffsetMs: 5, durationMs: 10 },
    ],
    foundInTenants: ["ticket-workflow", "agent-runtime"],
    unavailableTenants: [],
    ...overrides,
  };
}

describe("TraceWaterfall — SPEC-SC-014", () => {
  it("renders nested spans proportional to their real duration and offset", async () => {
    server.use(http.get(TRACE_URL, () => HttpResponse.json(traceResponse())));

    renderWithProviders(<TraceWaterfall traceId="trace-1" />);

    const rows = await screen.findAllByTestId("waterfall-row");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("handle-ticket");
    expect(rows[0]).toHaveAttribute("data-depth", "0");
    expect(rows[1]).toHaveTextContent("escalate");
    expect(rows[1]).toHaveAttribute("data-depth", "1");
  });

  it("SPEC-SC-014 §16: a real 404 renders an honest 'no longer available' state, not a blank/broken view", async () => {
    server.use(http.get(TRACE_URL, () => HttpResponse.json({ error: { code: "TRACE_NOT_FOUND", message: "not found" } }, { status: 404 })));

    renderWithProviders(<TraceWaterfall traceId="trace-1" />);

    expect(await screen.findByTestId("trace-not-found")).toHaveTextContent(/no longer available/i);
    expect(screen.queryByRole("button", { name: /retry/i })).not.toBeInTheDocument();
  });

  it("a real 503 renders a distinct, retryable unavailable state, and retry recovers", async () => {
    let callCount = 0;
    server.use(http.get(TRACE_URL, () => {
      callCount += 1;
      return callCount === 1
        ? HttpResponse.json({ error: { code: "TRACE_QUERY_UNAVAILABLE", message: "unavailable" } }, { status: 503 })
        : HttpResponse.json(traceResponse());
    }));
    const user = userEvent.setup();
    renderWithProviders(<TraceWaterfall traceId="trace-1" />);

    expect(await screen.findByTestId("trace-unavailable")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /retry/i }));

    await waitFor(() => expect(screen.queryByTestId("trace-unavailable")).not.toBeInTheDocument());
    expect(await screen.findAllByTestId("waterfall-row")).toHaveLength(2);
  });

  it("SPEC-SC-014 (ADR-0011): a partial outage still renders whatever real spans were found, with the gap reported honestly", async () => {
    server.use(http.get(TRACE_URL, () => HttpResponse.json(traceResponse({ unavailableTenants: ["policy-approval-governance"] }))));

    renderWithProviders(<TraceWaterfall traceId="trace-1" />);

    expect(await screen.findByTestId("trace-partial-outage")).toHaveTextContent("policy-approval-governance");
    expect(await screen.findAllByTestId("waterfall-row")).toHaveLength(2);
  });
});
