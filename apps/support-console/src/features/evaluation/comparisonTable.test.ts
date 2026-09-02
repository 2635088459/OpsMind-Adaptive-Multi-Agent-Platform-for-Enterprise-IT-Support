import { describe, it, expect } from "vitest";
import { buildComparisonRows } from "@/features/evaluation/comparisonTable";
import type { ScoreView } from "@/features/evaluation/types";

function score(overrides: Partial<ScoreView>): ScoreView {
  return { score_id: "s1", run_id: "r1", test_case_id: "tc1", dimension: "accuracy", score: 0.9, passed: true, grader_type: "deterministic", grader_version: "v1", failure_code: null, ...overrides };
}

describe("buildComparisonRows — SPEC-SC-015", () => {
  it("averages each dimension independently across baseline and candidate", () => {
    const baseline = [score({ dimension: "accuracy", score: 0.8 }), score({ dimension: "accuracy", score: 1.0 })];
    const candidate = [score({ dimension: "accuracy", score: 0.9 })];

    const rows = buildComparisonRows(baseline, candidate);

    expect(rows).toEqual([{ dimension: "accuracy", baselineAverage: 0.9, candidateAverage: 0.9, isRegression: false }]);
  });

  it("flags a regression when the candidate scores meaningfully worse than the baseline on a dimension", () => {
    const baseline = [score({ dimension: "safety", score: 1.0 })];
    const candidate = [score({ dimension: "safety", score: 0.5 })];

    const rows = buildComparisonRows(baseline, candidate);

    expect(rows[0].isRegression).toBe(true);
  });

  it("does not flag a regression when the candidate ties or improves on the baseline", () => {
    const baseline = [score({ dimension: "safety", score: 0.8 })];
    const candidate = [score({ dimension: "safety", score: 0.9 })];

    expect(buildComparisonRows(baseline, candidate)[0].isRegression).toBe(false);
  });

  it("includes a dimension present on only one side with a null average for the other, never fabricating a number", () => {
    const candidate = [score({ dimension: "new-metric", score: 0.7 })];

    const rows = buildComparisonRows([], candidate);

    expect(rows).toEqual([{ dimension: "new-metric", baselineAverage: null, candidateAverage: 0.7, isRegression: false }]);
  });
});
