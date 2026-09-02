import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { BFF_BASE_URL } from "@/lib/env";
import { fetchTraceWaterfall } from "@/features/trace/api";

const TRACE_URL = `${BFF_BASE_URL}/api/v1/observability/traces/trace-1`;

describe("trace api — SPEC-SC-014 real contract", () => {
  it("fetchTraceWaterfall parses the real TraceWaterfallView shape", async () => {
    server.use(http.get(TRACE_URL, () => HttpResponse.json({
      traceId: "trace-1",
      spans: [{ spanId: "a", parentSpanId: null, name: "op", serviceName: "ticket-workflow-service", tenant: "ticket-workflow", kind: "SPAN_KIND_SERVER", statusCode: "STATUS_CODE_OK", startOffsetMs: 0, durationMs: 30 }],
      foundInTenants: ["ticket-workflow"],
      unavailableTenants: [],
    })));

    const result = await fetchTraceWaterfall("trace-1");

    expect(result.spans).toHaveLength(1);
    expect(result.spans[0].spanId).toBe("a");
    expect(result.foundInTenants).toEqual(["ticket-workflow"]);
  });

  it("a real 404 TRACE_NOT_FOUND throws a typed ApiError", async () => {
    server.use(http.get(TRACE_URL, () => HttpResponse.json({ error: { code: "TRACE_NOT_FOUND", message: "The trace was not found." } }, { status: 404 })));

    await expect(fetchTraceWaterfall("trace-1")).rejects.toMatchObject({ status: 404, code: "TRACE_NOT_FOUND" });
  });

  it("a real 503 TRACE_QUERY_UNAVAILABLE throws a typed, retryable ApiError", async () => {
    server.use(http.get(TRACE_URL, () => HttpResponse.json({ error: { code: "TRACE_QUERY_UNAVAILABLE", message: "The trace store is currently unavailable." } }, { status: 503 })));

    await expect(fetchTraceWaterfall("trace-1")).rejects.toMatchObject({ status: 503, code: "TRACE_QUERY_UNAVAILABLE" });
  });
});
