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

function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
