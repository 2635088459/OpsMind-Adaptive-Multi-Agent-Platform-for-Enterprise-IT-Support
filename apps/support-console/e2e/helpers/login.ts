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
 * Full real login: app -> "Sign in" -> BFF
 * (/oauth2/authorization/support-console) -> Keycloak form -> back to the
 * BFF (sets OPSMIND_SESSION) -> back to the app on the queue view.
 */
export async function loginAsSupportAgent(page: Page): Promise<void> {
  await page.goto("/");

  const signInButton = page.getByRole("button", { name: /^sign in$/i });
  await expect(signInButton).toBeVisible();
  await signInButton.click();

  await page.waitForURL(/\/realms\/opsmind\/protocol\/openid-connect\/auth/, { timeout: 20_000 });
  await page.locator("#username").fill(SUPPORT_USERNAME);
  await page.locator("#password").fill(SUPPORT_PASSWORD);
  await page.locator("#kc-login").click();

  // The queue heading is the stable post-login landmark (SPEC-SC-001 §9).
  await expect(page.getByRole("heading", { name: "Queue" })).toBeVisible({ timeout: 20_000 });
}
