import { useState } from "react";
import { TraceWaterfall } from "@/features/trace/TraceWaterfall";
import { EvaluationComparisonTable } from "@/features/evaluation/EvaluationComparisonTable";
import { GRAFANA_BASE_URL, LANGSMITH_BASE_URL } from "@/lib/env";

/**
 * "Observability · Evaluation" (UC-SC-05 + UC-SC-06) — the page the Agent
 * Observability mockup describes, split into two deliberately-separate
 * sections (OpenTelemetry and evaluation answer different questions and must
 * not be merged into one view — see the frontend product vision).
 *
 * Neither section is where real troubleshooting happens: per UC-SC-05 the
 * in-viewport waterfall is "a marketing/preview-level simplification only"
 * and per UC-SC-06 the comparison is a summary — both link OUT (to Tempo via
 * Grafana, and to LangSmith) for the full experience. The ids are entered by
 * hand here because no backend surface hands this console a list of trace
 * ids or evaluation run ids today (a real, flagged gap: SPEC-TW-006's
 * timeline item carries no traceId, and evaluation-improvement-service
 * exposes only per-id reads).
 */
export function ObservabilityPage() {
  const [traceId, setTraceId] = useState("");
  const [submittedTraceId, setSubmittedTraceId] = useState("");
  const [runId, setRunId] = useState("");
  const [submittedRunId, setSubmittedRunId] = useState("");

  return (
    <div className="flex flex-col gap-8" data-testid="observability-page">
      <div>
        <h1 className="text-xl font-semibold text-ink">Observability &amp; Evaluation</h1>
        <p className="mt-1 text-sm text-ink-muted">
          A preview of a request&apos;s cross-service trace and of an agent version&apos;s evaluation scores. Open either in its own tool for the full picture.
        </p>
      </div>

      <section className="rounded-xl border border-border bg-surface p-4">
        <h2 className="text-sm font-semibold text-ink">Request trace (OpenTelemetry / Tempo)</h2>
        <form
          className="mt-3 flex flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            setSubmittedTraceId(traceId.trim());
          }}
        >
          <input
            aria-label="Trace ID"
            data-testid="trace-id-input"
            value={traceId}
            onChange={(e) => setTraceId(e.target.value)}
            placeholder="Paste a trace ID"
            className="min-w-[22rem] flex-1 rounded-md border border-border bg-surface px-3 py-1.5 font-mono text-sm text-ink"
          />
          <button type="submit" className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700">
            Preview
          </button>
          {submittedTraceId ? (
            <a
              href={`${GRAFANA_BASE_URL}/explore?traceID=${encodeURIComponent(submittedTraceId)}`}
              target="_blank"
              rel="noreferrer"
              data-testid="open-in-tempo"
              className="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-surface-muted"
            >
              Open in Tempo ↗
            </a>
          ) : null}
        </form>

        {submittedTraceId ? (
          <div className="mt-4">
            <TraceWaterfall traceId={submittedTraceId} />
          </div>
        ) : (
          <p className="mt-4 text-sm text-ink-muted" data-testid="trace-idle">
            Enter a trace ID to preview its span waterfall.
          </p>
        )}
      </section>

      <section className="rounded-xl border border-border bg-surface p-4">
        <h2 className="text-sm font-semibold text-ink">Agent version evaluation (LangSmith-style)</h2>
        <form
          className="mt-3 flex flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            setSubmittedRunId(runId.trim());
          }}
        >
          <input
            aria-label="Evaluation run ID"
            data-testid="run-id-input"
            value={runId}
            onChange={(e) => setRunId(e.target.value)}
            placeholder="Evaluation run ID"
            className="min-w-[22rem] flex-1 rounded-md border border-border bg-surface px-3 py-1.5 font-mono text-sm text-ink"
          />
          <button type="submit" className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700">
            Compare
          </button>
          {submittedRunId ? (
            <a
              href={`${LANGSMITH_BASE_URL}/`}
              target="_blank"
              rel="noreferrer"
              data-testid="open-in-langsmith"
              className="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-surface-muted"
            >
              View in LangSmith ↗
            </a>
          ) : null}
        </form>

        {submittedRunId ? (
          <div className="mt-4">
            <EvaluationComparisonTable runId={submittedRunId} />
          </div>
        ) : (
          <p className="mt-4 text-sm text-ink-muted" data-testid="evaluation-idle">
            Enter an evaluation run ID to compare it against its baseline.
          </p>
        )}
      </section>
    </div>
  );
}
