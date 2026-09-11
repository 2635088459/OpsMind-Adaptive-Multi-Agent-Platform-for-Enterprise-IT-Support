import type { MetricComparisonRow, ScoreView } from "@/features/evaluation/types";

function average(values: number[]): number | null {
  if (values.length === 0) return null;
  return values.reduce((sum, v) => sum + v, 0) / values.length;
}

/**
 * Real bug caught live 2026-09-11: a dimension like `HANDOFF_COMPLETENESS`
 * is "quality-only, UNSCORED unless the dataset carries handoff ground
 * truth" (agent-accuracy-eval.sh's own established convention, mirroring
 * how evaluation-improvement-service itself marks these rows) — the backend
 * still returns a placeholder `ScoreResponse` for it with `score: 0`, real
 * `failure_code: "UNSCORED"`. Averaging that placeholder in alongside real
 * scores rendered a confident "0.00" that reads as "the agent failed this
 * completely," when the honest fact is "nothing graded this at all" — the
 * exact distinction the table already draws correctly for a dimension with
 * NO rows on one side (a real `null` → "—"). An `UNSCORED` row is excluded
 * from the average the same way; if every row for a dimension is
 * `UNSCORED`, that side now correctly reads "—" too.
 */
function scoredValues(scores: ScoreView[], dimension: string): number[] {
  return scores.filter((s) => s.dimension === dimension && s.failure_code !== "UNSCORED").map((s) => s.score);
}

/** SPEC-SC-015 §9: groups both runs' own raw scores by `dimension` (metric column) and averages each — domain 07 owns the scoring itself, this is purely a client-side aggregation of numbers it already returned. */
export function buildComparisonRows(baselineScores: ScoreView[], candidateScores: ScoreView[]): MetricComparisonRow[] {
  const dimensions = new Set([...baselineScores, ...candidateScores].map((s) => s.dimension));
  return [...dimensions].sort().map((dimension) => {
    const baselineAverage = average(scoredValues(baselineScores, dimension));
    const candidateAverage = average(scoredValues(candidateScores, dimension));
    const isRegression = baselineAverage !== null && candidateAverage !== null && candidateAverage < baselineAverage;
    return { dimension, baselineAverage, candidateAverage, isRegression };
  });
}
