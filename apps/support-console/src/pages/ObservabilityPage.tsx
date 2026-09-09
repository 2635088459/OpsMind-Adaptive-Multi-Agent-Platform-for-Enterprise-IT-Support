import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { TraceWaterfall } from "@/features/trace/TraceWaterfall";
import { EvaluationComparisonTable } from "@/features/evaluation/EvaluationComparisonTable";
import { fetchDatasets, fetchLangsmithLink, fetchRuns } from "@/features/evaluation/api";
import type { RunView } from "@/features/evaluation/types";
import { GRAFANA_BASE_URL, LANGSMITH_BASE_URL, LANGSMITH_ORG_ID } from "@/lib/env";

/**
 * "Observability · Evaluation" (UC-SC-05 + UC-SC-06). Two deliberately-separate
 * sections — OpenTelemetry traces and agent-version evaluation answer different
 * questions. Each is a preview; the "open in ..." links go out to the full tool.
 *
 * The evaluation section now lists the real runs from evaluation-improvement-service
 * so an operator picks one instead of pasting a UUID. Traces still have no list
 * endpoint (a flagged gap — SPEC-TW-006's timeline item carries no traceId), so a
 * trace id is pasted by hand; the field validates the 32-hex shape and the copy
 * says where to get one.
 */

const _TRACE_ID_RE = /^[0-9a-f]{32}$/i;

/** `{base}/o/{org}/projects/p/{projectId}` — null unless the run has a real link AND an org id is configured. */
function langsmithProjectUrl(experimentRef: string | null | undefined): string | null {
  if (!experimentRef || !LANGSMITH_ORG_ID) return null;
  return `${LANGSMITH_BASE_URL}/o/${encodeURIComponent(LANGSMITH_ORG_ID)}/projects/p/${encodeURIComponent(experimentRef)}`;
}

export function ObservabilityPage() {
  return (
    <div className="flex flex-col gap-8" data-testid="observability-page">
      <div>
        <h1 className="text-xl font-semibold text-ink">Observability &amp; Evaluation</h1>
        <p className="mt-1 text-sm text-ink-muted">
          Preview a request&apos;s cross-service trace, or compare an agent version&apos;s evaluation scores against its
          baseline. Both are summaries — open either in its own tool for the full picture.
        </p>
      </div>

      <TraceSection />
      <EvaluationSection />
    </div>
  );
}

function TraceSection() {
  const [traceId, setTraceId] = useState("");
  const [submittedTraceId, setSubmittedTraceId] = useState("");
  const [invalid, setInvalid] = useState(false);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const value = traceId.trim();
    if (!_TRACE_ID_RE.test(value)) {
      setInvalid(true);
      setSubmittedTraceId("");
      return;
    }
    setInvalid(false);
    setSubmittedTraceId(value);
  };

  return (
    <section className="rounded-xl border border-border bg-surface p-4">
      <h2 className="text-sm font-semibold text-ink">Request trace</h2>
      <p className="mt-1 text-xs leading-relaxed text-ink-muted">
        The span waterfall for one request across every service it touched (OpenTelemetry → Grafana Tempo). Every ticket
        detail page now shows its own <span className="font-medium text-ink">trace</span> id (with a copy button) next to
        the title — paste one here, or grab one from a service log line. If a trace comes back empty, this environment
        isn&apos;t exporting spans to Tempo yet (the id is still real, there&apos;s just nothing stored behind it).
      </p>
      <form className="mt-3 flex flex-wrap items-center gap-2" onSubmit={submit}>
        <input
          aria-label="Trace ID"
          data-testid="trace-id-input"
          value={traceId}
          onChange={(e) => {
            setTraceId(e.target.value);
            if (invalid) setInvalid(false);
          }}
          placeholder="32-hex trace ID, e.g. 4b0c1e2f3a4b5c6d7e8f9a0b1c2d3e4f"
          className="min-w-[24rem] flex-1 rounded-md border border-border bg-surface px-3 py-1.5 font-mono text-sm text-ink"
        />
        <button
          type="submit"
          className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700"
        >
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

      {invalid ? (
        <p className="mt-3 text-sm text-danger" data-testid="trace-invalid">
          That doesn&apos;t look like a trace ID — expected 32 hexadecimal characters (0-9, a-f). Grab one from a
          ticket&apos;s <span className="font-medium">Open trace in Tempo</span> link.
        </p>
      ) : submittedTraceId ? (
        <div className="mt-4">
          <TraceWaterfall traceId={submittedTraceId} />
        </div>
      ) : (
        <p className="mt-4 text-sm text-ink-muted" data-testid="trace-idle">
          Enter a trace ID to preview its span waterfall.
        </p>
      )}
    </section>
  );
}

function EvaluationSection() {
  const [submittedRunId, setSubmittedRunId] = useState("");
  const [manualRunId, setManualRunId] = useState("");

  const datasetsQuery = useQuery({ queryKey: ["evaluation-datasets"], queryFn: fetchDatasets });
  const datasetIds = useMemo(
    () => (datasetsQuery.data ?? []).map((d) => d.dataset_id),
    [datasetsQuery.data],
  );
  const runsQuery = useQuery({
    queryKey: ["evaluation-runs", datasetIds],
    queryFn: async () => {
      const all = await Promise.all(datasetIds.map((id) => fetchRuns(id)));
      return all.flat().sort((a, b) => (a.started_at < b.started_at ? 1 : -1));
    },
    enabled: datasetIds.length > 0,
  });

  const runs = runsQuery.data ?? [];

  const langsmithQuery = useQuery({
    queryKey: ["langsmith-link", submittedRunId],
    queryFn: () => fetchLangsmithLink(submittedRunId),
    enabled: submittedRunId.length > 0,
  });
  const langsmithHref = langsmithQuery.data?.enabled
    ? langsmithProjectUrl(langsmithQuery.data.experiment_ref)
    : null;

  const submitManual = (e: React.FormEvent) => {
    e.preventDefault();
    setSubmittedRunId(manualRunId.trim());
  };

  return (
    <section className="rounded-xl border border-border bg-surface p-4">
      <h2 className="text-sm font-semibold text-ink">Agent version evaluation</h2>
      <p className="mt-1 text-xs leading-relaxed text-ink-muted">
        A candidate agent version&apos;s per-dimension scores vs. its baseline, plus the gate decision. Pick a run below —
        the table underneath <span className="font-medium text-ink">is</span> the full result (scores, regressions,
        recommendation), read straight from evaluation-improvement-service. When a run was recorded with LangSmith
        linkage enabled, a <span className="font-medium text-ink">View in LangSmith</span> deep link to that run&apos;s
        experiment appears too.
      </p>

      <div className="mt-3 overflow-x-auto rounded-lg border border-border">
        <table className="w-full min-w-[40rem] text-left text-sm">
          <thead className="bg-surface-muted text-xs uppercase text-ink-muted">
            <tr>
              <th className="px-3 py-2 font-medium">Run</th>
              <th className="px-3 py-2 font-medium">Target → baseline</th>
              <th className="px-3 py-2 font-medium">Status</th>
              <th className="px-3 py-2 font-medium">Started</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody data-testid="evaluation-run-list">
            {runsQuery.isLoading || datasetsQuery.isLoading ? (
              <tr>
                <td colSpan={5} className="px-3 py-4 text-ink-muted">
                  Loading runs…
                </td>
              </tr>
            ) : runsQuery.isError || datasetsQuery.isError ? (
              <tr>
                <td colSpan={5} className="px-3 py-4 text-danger">
                  Could not load evaluation runs from evaluation-improvement-service.
                </td>
              </tr>
            ) : runs.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-3 py-4 text-ink-muted">
                  No evaluation runs yet. Seed one with <span className="font-mono">scripts/…</span> or trigger a run.
                </td>
              </tr>
            ) : (
              runs.map((run) => (
                <RunRow
                  key={run.run_id}
                  run={run}
                  selected={run.run_id === submittedRunId}
                  onSelect={() => {
                    setSubmittedRunId(run.run_id);
                    setManualRunId(run.run_id);
                  }}
                />
              ))
            )}
          </tbody>
        </table>
      </div>

      <form className="mt-3 flex flex-wrap items-center gap-2" onSubmit={submitManual}>
        <input
          aria-label="Evaluation run ID"
          data-testid="run-id-input"
          value={manualRunId}
          onChange={(e) => setManualRunId(e.target.value)}
          placeholder="…or paste a run ID"
          className="min-w-[22rem] flex-1 rounded-md border border-border bg-surface px-3 py-1.5 font-mono text-sm text-ink"
        />
        <button
          type="submit"
          className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700"
        >
          Compare
        </button>
        {langsmithHref ? (
          <a
            href={langsmithHref}
            target="_blank"
            rel="noreferrer"
            data-testid="open-in-langsmith"
            className="rounded-md border border-border px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-surface-muted"
          >
            View in LangSmith ↗
          </a>
        ) : null}
      </form>
      {submittedRunId && langsmithQuery.data?.enabled === false ? (
        <p className="mt-2 text-xs text-ink-muted">
          This run isn&apos;t linked to a LangSmith experiment (linkage runs in no-op mode here). The comparison below is
          the full result.
        </p>
      ) : null}

      {submittedRunId ? (
        <div className="mt-4">
          <EvaluationComparisonTable runId={submittedRunId} />
        </div>
      ) : (
        <p className="mt-4 text-sm text-ink-muted" data-testid="evaluation-idle">
          Pick a run above (or paste an ID) to compare it against its baseline.
        </p>
      )}
    </section>
  );
}

function RunRow({ run, selected, onSelect }: { run: RunView; selected: boolean; onSelect: () => void }) {
  return (
    <tr
      className={`cursor-pointer border-t border-border hover:bg-surface-muted ${selected ? "bg-brand-600/10" : ""}`}
      onClick={onSelect}
    >
      <td className="px-3 py-2 font-mono text-xs text-ink">{run.run_key}</td>
      <td className="px-3 py-2 text-ink">
        {run.target_version}
        {run.baseline_version ? <span className="text-ink-muted"> → {run.baseline_version}</span> : (
          <span className="text-ink-muted"> (baseline)</span>
        )}
      </td>
      <td className="px-3 py-2">
        <span
          className={`rounded px-1.5 py-0.5 text-xs font-medium ${
            run.status === "PASSED"
              ? "bg-emerald-500/15 text-emerald-600"
              : run.status === "FAILED"
                ? "bg-danger/15 text-danger"
                : "bg-surface-muted text-ink-muted"
          }`}
        >
          {run.status}
        </span>
      </td>
      <td className="px-3 py-2 text-xs text-ink-muted">{new Date(run.started_at).toLocaleString()}</td>
      <td className="px-3 py-2 text-right">
        <span className="text-xs font-medium text-brand-600">{selected ? "Selected" : "Compare"}</span>
      </td>
    </tr>
  );
}
