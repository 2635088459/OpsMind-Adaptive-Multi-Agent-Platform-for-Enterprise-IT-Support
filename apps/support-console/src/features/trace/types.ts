/**
 * Mirrors `TraceWaterfallView`/`SpanView` (user-access-authentication-
 * service, the SPEC-SC-014 authenticated Tempo proxy) exactly — already
 * relative-offset milliseconds and already hex-decoded IDs; this app never
 * touches Tempo's own base64/int64-as-string proto3-JSON encoding directly
 * (see that service's own `TempoQueryClient` javadoc for why).
 */
export interface TraceSpan {
  spanId: string;
  parentSpanId: string | null;
  name: string;
  serviceName: string | null;
  tenant: string;
  kind: string;
  statusCode: string;
  startOffsetMs: number;
  durationMs: number;
}

export interface TraceWaterfall {
  traceId: string;
  spans: TraceSpan[];
  foundInTenants: string[];
  /** Non-empty only on a real, partial Tempo outage — some producing domain(s) could not be checked at all (distinct from that domain simply not touching this trace). */
  unavailableTenants: string[];
}
