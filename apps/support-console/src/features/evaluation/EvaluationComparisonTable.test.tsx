import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { EVALUATION_IMPROVEMENT_BASE_URL } from "@/lib/env";
import { EvaluationComparisonTable } from "@/features/evaluation/EvaluationComparisonTable";

const BASE = `${EVALUATION_IMPROVEMENT_BASE_URL}/evaluation/runs/run-1`;

function runResponse(overrides: Record<string, unknown> = {}) {
  return {
    run_id: "run-1", run_key: "key-1", dataset_id: "ds-1", dataset_version: "1.0", target_version: "v2",
    baseline_version: "v1", status: "SCORED", triggered_by: "actor-1", started_at: "2026-09-02T00:00:00Z", completed_at: "2026-09-02T00:05:00Z",
    ...overrides,
  };
}

describe("EvaluationComparisonTable — SPEC-SC-015", () => {
  it("renders a baseline-vs-candidate table and visually flags a real regression", async () => {
    server.use(
      http.get(BASE, () => HttpResponse.json(runResponse())),
      http.get(`${BASE}/scores`, () => HttpResponse.json([
        { score_id: "c1", run_id: "run-1", test_case_id: "tc1", dimension: "safety", score: 0.5, passed: false, grader_type: "deterministic", grader_version: "v1", failure_code: "SAFETY_VIOLATION" },
      ])),
      http.get(`${BASE}/regression-report`, () => HttpResponse.json({
        report_id: "rep-1", run_id: "run-1", baseline_run_id: "run-0", overall_decision: "FAIL", critical_failures: ["safety"], recommendation: "BLOCK", created_at: "2026-09-02T00:05:00Z",
      })),
      http.get(`${EVALUATION_IMPROVEMENT_BASE_URL}/evaluation/runs/run-0/scores`, () => HttpResponse.json([
        { score_id: "b1", run_id: "run-0", test_case_id: "tc1", dimension: "safety", score: 1.0, passed: true, grader_type: "deterministic", grader_version: "v1", failure_code: null },
      ])),
    );

    renderWithProviders(<EvaluationComparisonTable runId="run-1" />);

    const row = await screen.findByTestId("metric-row");
    expect(row).toHaveTextContent("safety");
    const candidateCell = await screen.findByTestId("candidate-cell");
    expect(candidateCell).toHaveAttribute("data-regression", "true");
    expect(candidateCell).toHaveTextContent("0.50");
    expect(screen.queryByTestId("evaluation-no-baseline")).not.toBeInTheDocument();
  });

  it("SPEC-SC-015 §16: a run with no regression report yet renders candidate-only, not an error", async () => {
    server.use(
      http.get(BASE, () => HttpResponse.json(runResponse({ baseline_version: null }))),
      http.get(`${BASE}/scores`, () => HttpResponse.json([
        { score_id: "c1", run_id: "run-1", test_case_id: "tc1", dimension: "accuracy", score: 0.9, passed: true, grader_type: "deterministic", grader_version: "v1", failure_code: null },
      ])),
      http.get(`${BASE}/regression-report`, () => HttpResponse.json({ error: { code: "NOT_FOUND", message: "not found" } }, { status: 404 })),
    );

    renderWithProviders(<EvaluationComparisonTable runId="run-1" />);

    expect(await screen.findByTestId("evaluation-no-baseline")).toBeInTheDocument();
    const candidateCell = await screen.findByTestId("candidate-cell");
    expect(candidateCell).toHaveTextContent("0.90");
    expect(screen.getByTestId("candidate-cell")).toHaveAttribute("data-regression", "false");
  });

  it("a run that produced zero scores renders a genuine empty state, not a broken table", async () => {
    server.use(
      http.get(BASE, () => HttpResponse.json(runResponse())),
      http.get(`${BASE}/scores`, () => HttpResponse.json([])),
      http.get(`${BASE}/regression-report`, () => HttpResponse.json({ error: { code: "NOT_FOUND", message: "not found" } }, { status: 404 })),
    );

    renderWithProviders(<EvaluationComparisonTable runId="run-1" />);

    expect(await screen.findByTestId("evaluation-empty")).toBeInTheDocument();
  });
});
