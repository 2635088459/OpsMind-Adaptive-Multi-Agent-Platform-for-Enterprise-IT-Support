import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/mswServer";
import { renderWithProviders } from "@/test/renderWithProviders";
import { EVALUATION_IMPROVEMENT_BASE_URL } from "@/lib/env";
import { ObservabilityPage } from "@/pages/ObservabilityPage";

const _DATASET = {
  dataset_id: "ds-1", name: "Demo", version: "v1", domain: "it-support", status: "PUBLISHED",
  case_count: 3, created_at: "2026-09-08T00:00:00Z", published_at: "2026-09-08T00:00:00Z",
};
const _RUN = {
  run_id: "run-77", run_key: "seed-candidate-v1", dataset_id: "ds-1", dataset_version: "v1",
  target_version: "agent-v1.1.0", baseline_version: "agent-v1.0.0", status: "PASSED",
  triggered_by: "seed", started_at: "2026-09-08T22:00:00Z", completed_at: "2026-09-08T22:05:00Z",
};

function stubEvaluationList() {
  server.use(
    http.get(`${EVALUATION_IMPROVEMENT_BASE_URL}/evaluation/datasets`, () => HttpResponse.json([_DATASET])),
    http.get(`${EVALUATION_IMPROVEMENT_BASE_URL}/evaluation/runs`, () => HttpResponse.json([_RUN])),
    // no org id is configured in the test env, so the LangSmith link never renders
    // regardless of this payload — stubbed only to keep the request handled.
    http.get(`${EVALUATION_IMPROVEMENT_BASE_URL}/evaluation/runs/:runId/langsmith-link`, ({ params }) =>
      HttpResponse.json({ run_id: String(params.runId), enabled: true, experiment_ref: "proj-1" }),
    ),
  );
}

describe("ObservabilityPage — UC-SC-05 / UC-SC-06", () => {
  it("shows both idle sections before any id is entered", () => {
    stubEvaluationList();
    renderWithProviders(<ObservabilityPage />);
    expect(screen.getByTestId("trace-idle")).toBeInTheDocument();
    expect(screen.getByTestId("evaluation-idle")).toBeInTheDocument();
    expect(screen.queryByTestId("open-in-tempo")).not.toBeInTheDocument();
    // LangSmith isn't configured in the test env -> the link is not rendered.
    expect(screen.queryByTestId("open-in-langsmith")).not.toBeInTheDocument();
  });

  it("rejects a malformed trace id with an inline error instead of a failed fetch", async () => {
    stubEvaluationList();
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />);

    await user.type(screen.getByTestId("trace-id-input"), "abc123def456");
    await user.click(screen.getByRole("button", { name: "Preview" }));

    expect(screen.getByTestId("trace-invalid")).toBeInTheDocument();
    expect(screen.queryByTestId("open-in-tempo")).not.toBeInTheDocument();
  });

  it("previewing a valid 32-hex trace id mounts the waterfall and a Tempo deep link", async () => {
    stubEvaluationList();
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />);

    const id = "0123456789abcdef0123456789abcdef";
    await user.type(screen.getByTestId("trace-id-input"), id);
    await user.click(screen.getByRole("button", { name: "Preview" }));

    expect(screen.queryByTestId("trace-idle")).not.toBeInTheDocument();
    expect(screen.queryByTestId("trace-invalid")).not.toBeInTheDocument();
    const link = screen.getByTestId("open-in-tempo");
    expect(link).toHaveAttribute("href", expect.stringContaining(`traceID=${id}`));
    expect(link).toHaveAttribute("target", "_blank");
  });

  it("lists the real evaluation runs and selecting one mounts the comparison", async () => {
    stubEvaluationList();
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />);

    const list = await screen.findByTestId("evaluation-run-list");
    const row = await within(list).findByText("seed-candidate-v1");

    await user.click(row);

    expect(screen.queryByTestId("evaluation-idle")).not.toBeInTheDocument();
  });

  it("comparing a pasted run id mounts the evaluation table", async () => {
    stubEvaluationList();
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />);

    await user.type(screen.getByTestId("run-id-input"), "run-77");
    await user.click(screen.getByRole("button", { name: "Compare" }));

    expect(screen.queryByTestId("evaluation-idle")).not.toBeInTheDocument();
  });
});
