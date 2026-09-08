import { test, expect } from "@playwright/test";

/**
 * SPEC-SC-003 / SPEC-SC-004 / SPEC-SC-005: the queue view against the real
 * GET /api/v1/support/tickets. Depends on the `support_queues` JWT claim
 * (added to the support-console Keycloak client 2026-09-08) — without it the
 * endpoint returns 0 rows and this test would (correctly) see an empty queue.
 */
test("the queue loads real tickets from ticket-workflow-service", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Queue" })).toBeVisible();

  // Resolves to the table (or the honest empty state) — never stuck on the
  // skeleton, never the error card.
  await expect(page.getByTestId("queue-error")).toHaveCount(0);
  await expect(
    page.getByTestId("queue-table").or(page.getByTestId("queue-empty")),
  ).toBeVisible({ timeout: 20_000 });

  const rows = page.getByTestId("queue-row");
  if (await rows.count()) {
    // SPEC-SC-004: each row shows a priority chip and an SLA cell with a
    // real state (never a client-invented estimate).
    await expect(rows.first().getByTestId("priority-chip")).toBeVisible();
    await expect(rows.first().getByTestId("sla-display")).toHaveAttribute("data-sla-state", /.+/);
  }
});

test("opening a queue row navigates to the real ticket detail", async ({ page }) => {
  await page.goto("/");
  const rows = page.getByTestId("queue-row");
  await expect(page.getByTestId("queue-table").or(page.getByTestId("queue-empty"))).toBeVisible({ timeout: 20_000 });
  test.skip((await rows.count()) === 0, "no tickets in the queue to open");

  await rows.first().getByRole("link").first().click();
  await expect(page).toHaveURL(/\/tickets\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId("ticket-error")).toHaveCount(0);

  // SPEC-SC-006: the AI activity panel loads its 3-way aggregation
  // (timeline + governance audit + tool requests) and resolves - not stuck
  // on the skeleton.
  await expect(page.getByTestId("ai-log-panel")).toBeVisible({ timeout: 20_000 });
});
