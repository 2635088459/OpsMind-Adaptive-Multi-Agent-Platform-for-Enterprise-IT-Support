import type { MetricComparisonRow, ScoreView } from "@/features/evaluation/types";

function average(values: number[]): number | null {
  if (values.length === 0) return null;
  return values.reduce((sum, v) => sum + v, 0) / values.length;
}

/** SPEC-SC-015 §9: groups both runs' own raw scores by `dimension` (metric column) and averages each — domain 07 owns the scoring itself, this is purely a client-side aggregation of numbers it already returned. */
export function buildComparisonRows(baselineScores: ScoreView[], candidateScores: ScoreView[]): MetricComparisonRow[] {
  const dimensions = new Set([...baselineScores, ...candidateScores].map((s) => s.dimension));
  return [...dimensions].sort().map((dimension) => {
    const baselineAverage = average(baselineScores.filter((s) => s.dimension === dimension).map((s) => s.score));
    const candidateAverage = average(candidateScores.filter((s) => s.dimension === dimension).map((s) => s.score));
    const isRegression = baselineAverage !== null && candidateAverage !== null && candidateAverage < baselineAverage;
    return { dimension, baselineAverage, candidateAverage, isRegression };
  });
}
