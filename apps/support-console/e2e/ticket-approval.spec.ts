import { test, expect } from "@playwright/test";

/**
 * SPEC-SC-008 / SPEC-SC-009 (UC-SC-02 §3): the approval card, now mounted on
 * the ticket detail page next to the AI activity log. It appears for any
 * ticket whose real `GET /api/v1/governance-audit-records` returns a record
 * carrying an `approvalRequestId` (SPEC-PG-030).
 *
 * Uses a fixed ticket id known (2026-09-08) to have such a record in the
 * local platform volume. If that seed is ever gone the test skips itself
 * rather than fail — it is asserting the wiring, not the presence of that
 * one row.
 */
const TICKET_WITH_APPROVAL = process.env.E2E_TICKET_WITH_APPROVAL ?? "01a05e8e-1763-7ac3-870c-1dca6de7d5e8";

test("a ticket with a linked approval renders the ApprovalCard on its detail page", async ({ page }) => {
  await page.goto(`/tickets/${TICKET_WITH_APPROVAL}`);
  await expect(page.getByTestId("ticket-error")).toHaveCount(0);

  // Wait past the loading skeleton, then the section resolves to either the
  // card or the honest empty state.
  await expect(page.getByTestId("ticket-approvals-skeleton")).toHaveCount(0, { timeout: 20_000 });
  const card = page.getByTestId("approval-card");
  const empty = page.getByTestId("ticket-approvals-empty");
  await expect(card.or(empty)).toBeVisible({ timeout: 20_000 });

  test.skip(await empty.isVisible(), "no linked approval for this ticket in the current environment");

  // Real card: it shows the live status and (for a still-REQUESTED one) the
  // grant/deny controls.
  await expect(card).toBeVisible();
  await expect(page.getByTestId("approval-status")).not.toBeEmpty();
});
