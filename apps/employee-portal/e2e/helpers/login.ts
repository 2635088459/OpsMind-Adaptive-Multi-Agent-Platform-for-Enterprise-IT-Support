import { expect, type Page } from "@playwright/test";

/**
 * Credentials for the real `opsmind` Keycloak realm. `test.agent` is the
 * plain EMPLOYEE-defaulted user opsmind-realm.json ships for exactly this
 * purpose (see the realm file's own employee-test-client comment).
 */
export const EMPLOYEE_USERNAME = process.env.E2E_EMPLOYEE_USERNAME ?? "test.agent";
export const EMPLOYEE_PASSWORD = process.env.E2E_EMPLOYEE_PASSWORD ?? "test-password";

/**
 * Drives the full real login: app -> "Sign in" -> BFF
 * (/oauth2/authorization/opsmind) -> Keycloak's hosted form -> back to the
 * BFF (sets OPSMIND_SESSION) -> back to the app, which then exchanges the
 * session for an access token and renders the conversation composer.
 *
 * Leaves `page` on the authenticated home view.
 */
export async function loginAsEmployee(page: Page): Promise<void> {
  await page.goto("/");

  // AuthGate briefly shows "Checking your session…" then the login screen.
  const signInButton = page.getByRole("button", { name: /sign in with company account/i });
  await expect(signInButton).toBeVisible();
  await signInButton.click();

  // Keycloak's own hosted login form (standard element ids).
  await page.waitForURL(/\/realms\/opsmind\/protocol\/openid-connect\/auth/, { timeout: 20_000 });
  await page.locator("#username").fill(EMPLOYEE_USERNAME);
  await page.locator("#password").fill(EMPLOYEE_PASSWORD);
  await page.locator("#kc-login").click();

  // Back on the app, authenticated: the message composer is the stable
  // post-login landmark (SPEC-EP-005).
  await expect(page.getByPlaceholder("Describe your issue…")).toBeVisible({ timeout: 20_000 });
}
