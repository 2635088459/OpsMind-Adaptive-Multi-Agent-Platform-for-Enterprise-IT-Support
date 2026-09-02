import { describe, it, expect } from "vitest";
import { buildWaterfallRows, traceTotalDurationMs } from "@/features/trace/waterfallLayout";
import type { TraceSpan } from "@/features/trace/types";

function span(overrides: Partial<TraceSpan>): TraceSpan {
  return {
    spanId: "s1", parentSpanId: null, name: "op", serviceName: "svc", tenant: "ticket-workflow",
    kind: "SPAN_KIND_SERVER", statusCode: "STATUS_CODE_OK", startOffsetMs: 0, durationMs: 10,
    ...overrides,
  };
}

describe("buildWaterfallRows — SPEC-SC-014", () => {
  it("orders a parent before its children, depth-first, not merely by start time", () => {
    const spans = [
      span({ spanId: "child", parentSpanId: "root", startOffsetMs: 5, durationMs: 5 }),
      span({ spanId: "root", parentSpanId: null, startOffsetMs: 0, durationMs: 20 }),
    ];

    const rows = buildWaterfallRows(spans);

    expect(rows.map((r) => r.span.spanId)).toEqual(["root", "child"]);
    expect(rows[0].depth).toBe(0);
    expect(rows[1].depth).toBe(1);
  });

  it("treats a span whose parentSpanId points outside this trace's own span set as its own root, not a dropped orphan (ADR-0011: a parent may have landed under an Unavailable tenant)", () => {
    const spans = [span({ spanId: "orphan", parentSpanId: "some-other-tenant-span-never-returned" })];

    const rows = buildWaterfallRows(spans);

    expect(rows).toHaveLength(1);
    expect(rows[0].depth).toBe(0);
  });

  it("sorts sibling spans under the same parent by their own start offset", () => {
    const spans = [
      span({ spanId: "second", parentSpanId: "root", startOffsetMs: 10 }),
      span({ spanId: "first", parentSpanId: "root", startOffsetMs: 2 }),
      span({ spanId: "root", parentSpanId: null, startOffsetMs: 0, durationMs: 30 }),
    ];

    const rows = buildWaterfallRows(spans);

    expect(rows.map((r) => r.span.spanId)).toEqual(["root", "first", "second"]);
  });
});

describe("traceTotalDurationMs", () => {
  it("is the furthest end offset across every span", () => {
    const spans = [span({ startOffsetMs: 0, durationMs: 10 }), span({ spanId: "s2", startOffsetMs: 5, durationMs: 20 })];
    expect(traceTotalDurationMs(spans)).toBe(25);
  });

  it("never returns zero even for a single zero-duration span, so percentage math never divides by zero", () => {
    expect(traceTotalDurationMs([span({ startOffsetMs: 0, durationMs: 0 })])).toBe(1);
  });
});
