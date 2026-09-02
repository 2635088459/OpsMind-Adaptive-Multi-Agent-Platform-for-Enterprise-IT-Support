/**
 * A real W3C traceparent header (`12-observability-and-audit` §1: "traceparent
 * header is generated for this call") for every outbound request this app
 * makes — `00-{trace-id}-{parent-id}-01` per the spec, generated client-side
 * since this app originates the trace (no upstream caller to continue from).
 */
export function newTraceparent(): string {
  const traceId = randomHex(16);
  const parentId = randomHex(8);
  return `00-${traceId}-${parentId}-01`;
}

/**
 * SPEC-SC-020: the shared-parent-span case — SPEC-SC-006's 3 concurrent
 * aggregation calls (timeline/governance-audit/tool-request) should produce
 * one connected trace (one common parent span), not 3 disconnected ones.
 * `newTraceparent()` alone can't express that: every call generates a fresh,
 * unrelated trace-id. A caller that owns "one logical operation fanning out
 * to N backends" creates one context up front and passes the same
 * `traceparent()` value to every fan-out call.
 */
export interface TraceContext {
  traceparent(): string;
}

export function newTraceContext(): TraceContext {
  const traceId = randomHex(16);
  const parentSpanId = randomHex(8);
  return { traceparent: () => `00-${traceId}-${parentSpanId}-01` };
}

function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
