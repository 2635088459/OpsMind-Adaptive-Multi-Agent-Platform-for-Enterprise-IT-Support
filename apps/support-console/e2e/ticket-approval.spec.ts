import { test, expect, type Page } from "@playwright/test";

/**
 * SPEC-SC-008 / SPEC-SC-009 (UC-SC-02 §3): the approval card mounted on the
 * ticket detail page. SPEC-SC-014 (UC-SC-05): the "Open trace in Tempo" deep
 * link in the detail header.
 *
 * Both use a fixed ticket id known (2026-09-08) to exist with real activity
 * in a long-lived local platform volume. A fresh environment (CI) has no
 * such ticket — each test skips itself rather than fail when the detail
 * view 404s. They assert the *wiring*, not the presence of that one row.
 */
const TICKET_WITH_APPROVAL = process.env.E2E_TICKET_WITH_APPROVAL ?? "01a05e8e-1763-7ac3-870c-1dca6de7d5e8";

async function openTicketOrSkip(page: Page): Promise<void> {
  await page.goto(`/tickets/${TICKET_WITH_APPROVAL}`);
  const error = page.getByTestId("ticket-error");
  const loaded = page.getByTestId("ticket-approvals-skeleton").or(page.getByTestId("ticket-approvals"))
    .or(page.getByTestId("ticket-approvals-empty"));
  await expect(error.or(loaded)).toBeVisible({ timeout: 20_000 });
  test.skip(await error.isVisible(), `ticket ${TICKET_WITH_APPROVAL} not present in this environment`);
}

test("a ticket with a linked approval renders the ApprovalCard on its detail page", async ({ page }) => {
  await openTicketOrSkip(page);

  await expect(page.getByTestId("ticket-approvals-skeleton")).toHaveCount(0, { timeout: 20_000 });
  const card = page.getByTestId("approval-card");
  const empty = page.getByTestId("ticket-approvals-empty");
  await expect(card.or(empty)).toBeVisible({ timeout: 20_000 });

  test.skip(await empty.isVisible(), "no linked approval for this ticket in the current environment");

  await expect(card).toBeVisible();
  await expect(page.getByTestId("approval-status")).not.toBeEmpty();
});

test("a ticket with prior activity shows an Open-trace-in-Tempo deep link @sc-014", async ({ page }) => {
  // SPEC-SC-014 / UC-SC-05: the real GET /api/v1/tickets/{id}/trace
  // (TicketTraceController) — a processed ticket has audit rows carrying a
  // real 32-hex trace id, so the detail header shows a Tempo deep link.
  await openTicketOrSkip(page);

  const link = page.getByTestId("ticket-trace-link");
  await expect(link).toBeVisible({ timeout: 20_000 });
  await expect(link).toHaveAttribute("href", /\/explore\?traceID=[0-9a-f]{32}$/);
  await expect(link).toHaveAttribute("target", "_blank");
});
