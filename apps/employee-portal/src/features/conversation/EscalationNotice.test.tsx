import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { EscalationNotice } from "@/features/conversation/EscalationNotice";

describe("EscalationNotice", () => {
  it("renders honestly: handed to a human, never claims resolved", () => {
    render(
      <EscalationNotice escalation={{ ticketId: "ticket-1", displayId: "TCK-100", reason: "needs hardware swap", assignedTeam: "Desktop Support" }} />,
    );

    const notice = screen.getByTestId("escalation-notice");
    expect(notice).toHaveTextContent(/handed to a human support agent/i);
    expect(notice).not.toHaveTextContent(/resolved|fixed|complete/i);
    expect(notice).toHaveTextContent(/desktop support/i);
    expect(notice).toHaveTextContent(/needs hardware swap/i);
  });

  it("never invents a resolution-time promise the backend did not provide", () => {
    render(<EscalationNotice escalation={{ ticketId: "ticket-1", displayId: null, reason: null, assignedTeam: null }} />);

    expect(screen.getByTestId("escalation-notice")).not.toHaveTextContent(/within \d|hours|minutes/i);
  });
});
