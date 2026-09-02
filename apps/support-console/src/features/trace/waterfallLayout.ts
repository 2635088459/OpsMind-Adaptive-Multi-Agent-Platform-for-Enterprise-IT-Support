import type { TraceSpan } from "@/features/trace/types";

export interface WaterfallRow {
  span: TraceSpan;
  depth: number;
}

/**
 * SPEC-SC-014 §9: "nested bars proportional to duration ... nesting depth."
 * Builds a depth-first row order from `parentSpanId` links — a span whose
 * own parent isn't present in this trace's own span list (a real,
 * expected case: ADR-0011 means a parent span can land under a DIFFERENT
 * tenant than a child, and that tenant's own query might come back
 * `Unavailable` rather than `Found`) is treated as its own root rather than
 * silently dropped.
 */
export function buildWaterfallRows(spans: TraceSpan[]): WaterfallRow[] {
  const byParent = new Map<string | null, TraceSpan[]>();
  const knownIds = new Set(spans.map((s) => s.spanId));
  for (const span of spans) {
    const parentKey = span.parentSpanId && knownIds.has(span.parentSpanId) ? span.parentSpanId : null;
    const siblings = byParent.get(parentKey) ?? [];
    siblings.push(span);
    byParent.set(parentKey, siblings);
  }
  for (const siblings of byParent.values()) {
    siblings.sort((a, b) => a.startOffsetMs - b.startOffsetMs);
  }

  const rows: WaterfallRow[] = [];
  function visit(parentKey: string | null, depth: number) {
    for (const span of byParent.get(parentKey) ?? []) {
      rows.push({ span, depth });
      visit(span.spanId, depth + 1);
    }
  }
  visit(null, 0);
  return rows;
}

/** The trace's own total span, for scaling every bar's left offset/width as a percentage. */
export function traceTotalDurationMs(spans: TraceSpan[]): number {
  return Math.max(1, ...spans.map((s) => s.startOffsetMs + s.durationMs));
}
