import { test, expect } from "@playwright/test";
import { ensureFreshConversation } from "./helpers/conversation";

/**
 * SPEC-EP-004 / SPEC-EP-005 / SPEC-EP-007-009: the core conversational loop,
 * end to end against the real agent-runtime-service (SPEC-ARO-038/039/040).
 *
 * NOTE ON REASONING MODE: agent-runtime picks its reasoning adapter from
 * `CONVERSATION_REASONING_MODE`. Under `openai`/`anthropic` with a valid key
 * the agent's decision (text vs. proposedAction vs. escalation) is
 * non-deterministic, so these tests assert on the *envelope* (a turn was
 * appended, the composer re-enabled, no error banner) rather than a specific
 * decision. Under `static` the keyword adapter is deterministic and the
 * proposed-action test below becomes exact.
 */

test("sends a first message and receives an agent turn", async ({ page }) => {
  await ensureFreshConversation(page);
  const composer = page.getByPlaceholder("Describe your issue…");

  await composer.fill("My VPN client will not connect from home since this morning.");
  await page.getByRole("button", { name: "Send" }).click();

  // The employee's own message shows immediately in the transcript.
  await expect(
    page.getByTestId("message-bubble").filter({ has: page.getByText("My VPN client will not connect from home since this morning.") }),
  ).toHaveAttribute("data-author", "employee");

  // A real agent turn comes back (text / proposed action / escalation) — the
  // composer re-enables and no agent-unavailable banner is shown.
  await expect(page.getByTestId("agent-unavailable-banner")).toHaveCount(0);
  await expect(composer).toBeEnabled({ timeout: 30_000 });
});

test("proposed-action turn renders a confirm/decline card @static-reasoning", async ({ page }) => {
  test.skip(
    process.env.E2E_REASONING_MODE !== "static",
    "Deterministic only when agent-runtime runs CONVERSATION_REASONING_MODE=static; " +
      "set E2E_REASONING_MODE=static to enable.",
  );

  await ensureFreshConversation(page);
  const composer = page.getByPlaceholder("Describe your issue…");
  await composer.fill("Please reset my password, I am locked out of my account.");
  await page.getByRole("button", { name: "Send" }).click();

  const card = page.getByTestId("proposed-action-card");
  await expect(card).toBeVisible({ timeout: 30_000 });
  await expect(card.getByRole("button", { name: "Confirm" })).toBeVisible();
  await expect(card.getByRole("button", { name: "Not now" })).toBeVisible();

  // Confirm dispatches the real SPEC-ARO-040 call; the card resolves to an
  // execution-status entry (done / still-processing / awaiting-approval).
  await card.getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByTestId("action-execution-status")).toBeVisible({ timeout: 30_000 });
});
