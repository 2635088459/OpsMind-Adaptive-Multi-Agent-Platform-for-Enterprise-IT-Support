import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { EVALUATION_IMPROVEMENT_BASE_URL } from "@/lib/env";
import { fetchRegressionReport, fetchRun, fetchScores } from "@/features/evaluation/api";

const BASE = `${EVALUATION_IMPROVEMENT_BASE_URL}/evaluation/runs/run-1`;

describe("evaluation api — SPEC-SC-015 real contract (snake_case wire shape)", () => {
  it("fetchRun maps the real RunResponse shape, snake_case fields intact", async () => {
    server.use(http.get(BASE, () => HttpResponse.json({
      run_id: "run-1", run_key: "key-1", dataset_id: "ds-1", dataset_version: "1.0", target_version: "v2",
      baseline_version: "v1", status: "SCORED", triggered_by: "actor-1", started_at: "2026-09-02T00:00:00Z", completed_at: "2026-09-02T00:05:00Z",
    })));

    const run = await fetchRun("run-1");

    expect(run.run_id).toBe("run-1");
    expect(run.baseline_version).toBe("v1");
  });

  it("fetchScores maps the real ScoreResponse list shape", async () => {
    server.use(http.get(`${BASE}/scores`, () => HttpResponse.json([
      { score_id: "s1", run_id: "run-1", test_case_id: "tc1", dimension: "accuracy", score: 0.95, passed: true, grader_type: "deterministic", grader_version: "v1", failure_code: null },
    ])));

    const scores = await fetchScores("run-1");

    expect(scores).toHaveLength(1);
    expect(scores[0].dimension).toBe("accuracy");
  });

  it("fetchRegressionReport returns the real report when one exists", async () => {
    server.use(http.get(`${BASE}/regression-report`, () => HttpResponse.json({
      report_id: "rep-1", run_id: "run-1", baseline_run_id: "run-0", overall_decision: "PASS", critical_failures: [], recommendation: "PROMOTE", created_at: "2026-09-02T00:05:00Z",
    })));

    const report = await fetchRegressionReport("run-1");

    expect(report?.baseline_run_id).toBe("run-0");
  });

  it("fetchRegressionReport returns null (not a thrown error) on a real 404 — a genuine 'never compared' state", async () => {
    server.use(http.get(`${BASE}/regression-report`, () => HttpResponse.json({ error: { code: "NOT_FOUND", message: "not found" } }, { status: 404 })));

    const report = await fetchRegressionReport("run-1");

    expect(report).toBeNull();
  });
});
