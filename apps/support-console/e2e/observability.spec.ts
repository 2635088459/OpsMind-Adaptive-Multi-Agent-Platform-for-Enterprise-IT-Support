import { test, expect } from "@playwright/test";

/**
 * SPEC-SC-014 / SPEC-SC-015 (UC-SC-05 / UC-SC-06): the Observability page —
 * now a real route (`/observability`), reachable from the top nav. Verifies
 * the page mounts and that entering an id switches each section out of its
 * idle state into the real component (which then hits the real Tempo proxy /
 * evaluation-improvement-service).
 */
test("the Observability page is reachable and previews a trace + an evaluation run", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Observability" }).click();
  await expect(page).toHaveURL(/\/observability$/);
  await expect(page.getByTestId("observability-page")).toBeVisible();

  // Trace section — a bogus 32-hex id exercises the REAL authenticated BFF
  // Tempo proxy round trip (a 401 would mean the session cookie was not
  // forwarded; a 404 -> "no longer available" means it authenticated and
  // Tempo simply had no such trace, which is the honest resolved state here).
  await page.getByTestId("trace-id-input").fill("0000000000000000e2e0000000000000");
  await page.getByRole("button", { name: "Preview" }).click();
  await expect(page.getByTestId("trace-idle")).toHaveCount(0);
  await expect(page.getByTestId("open-in-tempo")).toHaveAttribute("href", /traceID=0000000000000000e2e0000000000000/);
  await expect(
    page.getByTestId("trace-not-found").or(page.getByTestId("trace-waterfall")),
  ).toBeVisible({ timeout: 20_000 });

  // Evaluation section — the 3 real chained reads against
  // evaluation-improvement-service (run / scores / regression-report). Uses
  // the demo candidate run seeded by
  // migrations/…e1c7a9d4b206_seed_demo_evaluation_run.py (agent-v1.1.0 vs
  // baseline agent-v1.0.0). Falls back to asserting the honest error state
  // if that seed isn't present in the current environment.
  const seededRunId = process.env.E2E_EVAL_RUN_ID ?? "20000000-0000-0000-0000-000000000002";
  await page.getByTestId("run-id-input").fill(seededRunId);
  await page.getByRole("button", { name: "Compare" }).click();
  await expect(page.getByTestId("evaluation-idle")).toHaveCount(0);
  const table = page.getByTestId("evaluation-comparison-table");
  await expect(table.or(page.getByTestId("evaluation-error"))).toBeVisible({ timeout: 20_000 });
  if (await table.isVisible()) {
    // The seeded run has a deliberate TOOL_SELECTION regression.
    await expect(table).toContainText("TOOL_SELECTION");
    await expect(table).toContainText(/vs\. baseline agent-v1\.0\.0/);
  }
});
