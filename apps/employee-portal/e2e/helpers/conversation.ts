import { expect, type Page } from "@playwright/test";

/**
 * SPEC-EP-015: `test.agent` almost always has a prior non-`RUNNING`
 * conversation (escalated / tool-waiting / …) from an earlier run. The app
 * now resumes it as `RESUMED_CLOSED` — the composer is hidden behind a
 * "Start a new conversation" button, because sending into it would 409.
 *
 * Every spec that wants a live composer calls this first. The composer is
 * briefly visible on first paint (turn state defaults to IDLE) and only
 * disappears once `useResumeConversation` seeds RESUMED_CLOSED a tick after
 * its GET /conversations/most-recent resolves — so we give the banner a
 * short window to appear before deciding, then dismiss it if it did.
 */
export async function ensureFreshConversation(page: Page): Promise<void> {
  await page.goto("/");

  const composer = page.getByPlaceholder("Describe your issue…");
  const startNew = page.getByTestId("start-new-conversation");

  // Wait for the resume to settle: the banner shows (terminal/paused last
  // conversation) or it never will (RUNNING / no prior conversation).
  await startNew.waitFor({ state: "visible", timeout: 5_000 }).catch(() => {});
  if (await startNew.isVisible()) {
    await startNew.click();
  }
  await expect(composer).toBeVisible({ timeout: 15_000 });
}
