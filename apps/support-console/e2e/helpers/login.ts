import { expect, type Page } from "@playwright/test";

/**
 * `support.agent` carries realm role `support_agent` and, via the
 * support-console client's own hardcoded-claim mappers, `actor_type=IT_SUPPORT`
 * + `support_teams=["network-support-team"]` + (added 2026-09-08)
 * `support_queues=["HOUSING_PORTAL","EMAIL","VPN","OTHER"]` — the last one is
 * what makes the support queue / ticket views return anything at all.
 */
export const SUPPORT_USERNAME = process.env.E2E_SUPPORT_USERNAME ?? "support.agent";
export const SUPPORT_PASSWORD = process.env.E2E_SUPPORT_PASSWORD ?? "test-password";

/**
 * Full real login: app -> inline username/password form (the BFF's own
 * `PasswordLoginController` direct-grant login against this app's own
 * "support-console" Keycloak client registration — no navigation to
 * Keycloak's own hosted page) -> the queue view.
 */
export async function loginAsSupportAgent(page: Page): Promise<void> {
  await page.goto("/");

  await expect(page.getByLabel(/username/i)).toBeVisible();
  await page.getByLabel(/username/i).fill(SUPPORT_USERNAME);
  await page.getByLabel(/password/i).fill(SUPPORT_PASSWORD);
  await page.getByRole("button", { name: /^sign in$/i }).click();

  // The queue heading is the stable post-login landmark (SPEC-SC-001 §9).
  await expect(page.getByRole("heading", { name: "Queue" })).toBeVisible({ timeout: 20_000 });
}
