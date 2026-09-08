import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/renderWithProviders";
import { ObservabilityPage } from "@/pages/ObservabilityPage";

describe("ObservabilityPage — UC-SC-05 / UC-SC-06", () => {
  it("shows both idle sections before any id is entered", () => {
    renderWithProviders(<ObservabilityPage />);
    expect(screen.getByTestId("trace-idle")).toBeInTheDocument();
    expect(screen.getByTestId("evaluation-idle")).toBeInTheDocument();
    expect(screen.queryByTestId("open-in-tempo")).not.toBeInTheDocument();
    expect(screen.queryByTestId("open-in-langsmith")).not.toBeInTheDocument();
  });

  it("previewing a trace id mounts the waterfall and a Tempo deep link", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />);

    await user.type(screen.getByTestId("trace-id-input"), "abc123def456");
    await user.click(screen.getByRole("button", { name: "Preview" }));

    // TraceWaterfall mounts (it will show its own loading/error state against
    // the real proxy — here just assert the section switched out of idle).
    expect(screen.queryByTestId("trace-idle")).not.toBeInTheDocument();
    const link = screen.getByTestId("open-in-tempo");
    expect(link).toHaveAttribute("href", expect.stringContaining("traceID=abc123def456"));
    expect(link).toHaveAttribute("target", "_blank");
  });

  it("comparing a run id mounts the evaluation table and a LangSmith link", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />);

    await user.type(screen.getByTestId("run-id-input"), "run-77");
    await user.click(screen.getByRole("button", { name: "Compare" }));

    expect(screen.queryByTestId("evaluation-idle")).not.toBeInTheDocument();
    expect(screen.getByTestId("open-in-langsmith")).toHaveAttribute("target", "_blank");
  });
});
