import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProposedActionCard } from "@/features/conversation/ProposedActionCard";

const LONG_SUMMARY =
  "This is a realistically long proposed-action summary describing exactly what will happen: " +
  "your VPN client profile will be reset to the default corporate configuration, your cached " +
  "credentials will be cleared, and you will be prompted to sign in again the next time you connect. " +
  "This text is intentionally long enough to overflow a narrow mobile viewport if truncation were applied.";

describe("ProposedActionCard", () => {
  it("BI-EP-007: renders the summary with no truncation styling, at any length", () => {
    render(
      <ProposedActionCard
        action={{ actionId: "action-1", summary: LONG_SUMMARY, riskLevel: "LOW" }}
        onConfirm={vi.fn()}
        onDecline={vi.fn()}
        disabled={false}
      />,
    );

    const summary = screen.getByTestId("proposed-action-summary");
    expect(summary).toHaveTextContent(LONG_SUMMARY);
    expect(summary.className).not.toMatch(/truncate|ellipsis|line-clamp|overflow-hidden/);
  });

  it("forwards the correct actionId to onConfirm", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <ProposedActionCard action={{ actionId: "action-42", summary: "reset vpn", riskLevel: "LOW" }} onConfirm={onConfirm} onDecline={vi.fn()} disabled={false} />,
    );

    await user.click(screen.getByRole("button", { name: /confirm/i }));

    expect(onConfirm).toHaveBeenCalledWith("action-42");
  });

  it("forwards the correct actionId to onDecline", async () => {
    const user = userEvent.setup();
    const onDecline = vi.fn();
    render(
      <ProposedActionCard action={{ actionId: "action-42", summary: "reset vpn", riskLevel: "LOW" }} onConfirm={vi.fn()} onDecline={onDecline} disabled={false} />,
    );

    await user.click(screen.getByRole("button", { name: /not now/i }));

    expect(onDecline).toHaveBeenCalledWith("action-42");
  });

  it("disables both buttons while an action is already in flight", () => {
    render(
      <ProposedActionCard action={{ actionId: "action-1", summary: "reset vpn", riskLevel: "LOW" }} onConfirm={vi.fn()} onDecline={vi.fn()} disabled={true} />,
    );

    expect(screen.getByRole("button", { name: /confirm/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /not now/i })).toBeDisabled();
  });
});
