import { useEvaluationComparison } from "@/features/evaluation/useEvaluationComparison";

/**
 * SPEC-SC-015: a read-only baseline-vs-candidate comparison table sourced
 * from domain 07's real run/scoring model. Regressions (candidate scoring
 * worse than baseline on a dimension) reuse SPEC-SC-004's own severity
 * color convention (`bg-danger/10 text-danger`), not a new one invented
 * here.
 */
export function EvaluationComparisonTable({ runId }: { runId: string }) {
  const { isLoading, isError, run, hasBaseline, rows } = useEvaluationComparison(runId);

  if (isLoading) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4" data-testid="evaluation-skeleton">
        <div className="h-4 w-1/3 animate-pulse rounded bg-surface-muted" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="rounded-xl border border-border bg-danger/5 p-4 text-sm text-danger" data-testid="evaluation-error">
        Could not load evaluation data for this run.
      </div>
    );
  }

  if (rows.length === 0) {
    return (
      <div className="rounded-xl border border-border bg-surface p-4 text-sm text-ink-muted" data-testid="evaluation-empty">
        No evaluation runs are available for this scope yet.
      </div>
    );
  }

  return (
    <div className="rounded-xl border border-border bg-surface p-4" data-testid="evaluation-comparison-table">
      <h2 className="text-sm font-medium text-ink">Evaluation comparison</h2>
      {run && (
        <p className="mt-1 text-xs text-ink-muted">
          {run.target_version}
          {run.baseline_version ? ` vs. baseline ${run.baseline_version}` : ""}
        </p>
      )}

      {!hasBaseline && (
        <p className="mt-2 rounded-md bg-surface-muted px-3 py-2 text-xs text-ink-muted" data-testid="evaluation-no-baseline">
          No baseline comparison is available for this run yet — showing candidate scores only.
        </p>
      )}

      <table className="mt-3 w-full text-left text-sm">
        <thead className="border-b border-border text-xs uppercase tracking-wide text-ink-muted">
          <tr>
            <th className="py-1 pr-4">Metric</th>
            <th className="py-1 pr-4">Baseline</th>
            <th className="py-1 pr-4">Candidate</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.dimension} className="border-b border-border last:border-0" data-testid="metric-row">
              <td className="py-1 pr-4 text-ink">{row.dimension}</td>
              <td className="py-1 pr-4 text-ink-muted">{row.baselineAverage !== null ? row.baselineAverage.toFixed(2) : "—"}</td>
              <td className={`py-1 pr-4 ${row.isRegression ? "rounded bg-danger/10 font-medium text-danger" : "text-ink"}`} data-testid="candidate-cell" data-regression={row.isRegression}>
                {row.candidateAverage !== null ? row.candidateAverage.toFixed(2) : "—"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
