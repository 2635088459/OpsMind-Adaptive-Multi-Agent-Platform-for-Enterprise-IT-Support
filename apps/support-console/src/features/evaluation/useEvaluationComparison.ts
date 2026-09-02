import { useQuery } from "@tanstack/react-query";
import { fetchRegressionReport, fetchRun, fetchScores } from "@/features/evaluation/api";
import { buildComparisonRows } from "@/features/evaluation/comparisonTable";

/**
 * SPEC-SC-015: 3 dependent real reads, chained the same way SPEC-SC-008's
 * `ApprovalCard` chains fetch + hash before a decision is even possible —
 * the baseline run to compare against isn't known until the candidate
 * run's own regression report resolves it (`baseline_run_id`), so the
 * baseline scores query only enables once that's known.
 */
export function useEvaluationComparison(runId: string) {
  const runQuery = useQuery({ queryKey: ["evaluation-run", runId], queryFn: () => fetchRun(runId) });
  const candidateScoresQuery = useQuery({ queryKey: ["evaluation-scores", runId], queryFn: () => fetchScores(runId) });
  const reportQuery = useQuery({ queryKey: ["evaluation-report", runId], queryFn: () => fetchRegressionReport(runId) });

  const baselineRunId = reportQuery.data?.baseline_run_id ?? null;
  const baselineScoresQuery = useQuery({
    queryKey: ["evaluation-scores", baselineRunId],
    queryFn: () => fetchScores(baselineRunId as string),
    enabled: baselineRunId !== null,
  });

  const isLoading = runQuery.isLoading || candidateScoresQuery.isLoading || reportQuery.isLoading || (baselineRunId !== null && baselineScoresQuery.isLoading);
  const isError = runQuery.isError || candidateScoresQuery.isError || reportQuery.isError || baselineScoresQuery.isError;

  const rows = candidateScoresQuery.data
    ? buildComparisonRows(baselineScoresQuery.data ?? [], candidateScoresQuery.data)
    : [];

  return {
    isLoading,
    isError,
    run: runQuery.data ?? null,
    report: reportQuery.data ?? null,
    hasBaseline: baselineRunId !== null,
    rows,
  };
}
