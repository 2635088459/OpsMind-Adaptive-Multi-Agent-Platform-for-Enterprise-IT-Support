import { test, expect } from "@playwright/test";
import { ensureFreshConversation } from "./helpers/conversation";

/**
 * SPEC-EP-012 / SPEC-EP-013 / SPEC-EP-014: an escalation turn creates a real
 * ticket in ticket-workflow-service (via SPEC-ARO-041 triage), renders the
 * in-transcript notice, and opens the live ticket-status panel — which then
 * subscribes to the real SSE stream (GET /api/v1/tickets/{id}/events).
 *
 * Deterministic only under the static reasoning adapter (the "burning smell /
 * won't turn on" keywords force an escalation); with a real LLM the decision
 * is the model's to make.
 */
test("an escalation-worthy message opens the ticket-status panel @static-reasoning", async ({ page }) => {
  test.skip(
    process.env.E2E_REASONING_MODE !== "static",
    "Deterministic only when agent-runtime runs CONVERSATION_REASONING_MODE=static.",
  );

  // SPEC-EP-015: dismiss the resumed-closed banner if test.agent's last
  // conversation is a terminal/paused one (before the SPEC-EP-015 fix this
  // test was test.fixme — the composer stayed enabled, the send 409'd, and
  // it rendered a misleading "agent unavailable").
  await ensureFreshConversation(page);
  const composer = page.getByPlaceholder("Describe your issue…");
  await composer.fill("There is a burning smell coming from my laptop and it will not turn on.");
  await page.getByRole("button", { name: "Send" }).click();

  await expect(page.getByTestId("escalation-notice")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("escalation-notice")).toContainText(/Ticket .* was created/i);

  const panel = page.getByTestId("ticket-status-panel");
  await expect(panel).toBeVisible({ timeout: 20_000 });
  await expect(page.getByTestId("ticket-status-error")).toHaveCount(0);
  await expect(page.getByTestId("ticket-status-value")).not.toBeEmpty();
  await expect(page.getByTestId("stream-failed")).toHaveCount(0);
});
