import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ActionExecutionStatus } from "@/features/conversation/ActionExecutionStatus";

describe("ActionExecutionStatus", () => {
  it("renders 'done' as a real success, not a generic banner", () => {
    render(<ActionExecutionStatus outcome="done" />);
    expect(screen.getByTestId("action-execution-status")).toHaveTextContent(/done|completed successfully/i);
  });

  it("BI-EP-005: 'still-processing' never claims done", () => {
    render(<ActionExecutionStatus outcome="still-processing" />);
    const el = screen.getByTestId("action-execution-status");
    expect(el).toHaveTextContent(/still working/i);
    expect(el).not.toHaveTextContent(/^done|completed successfully/i);
  });

  it("BI-EP-005: 'awaiting-approval' is rendered as an honest 'waiting on a human' notice", () => {
    render(<ActionExecutionStatus outcome="awaiting-approval" />);
    const el = screen.getByTestId("action-execution-status");
    expect(el).toHaveTextContent(/waiting on a human/i);
    expect(el).not.toHaveTextContent(/completed successfully/i);
  });

  it("renders 'declined' distinctly from any executed outcome", () => {
    render(<ActionExecutionStatus outcome="declined" />);
    expect(screen.getByTestId("action-execution-status")).toHaveTextContent(/chose not to proceed/i);
  });
});
