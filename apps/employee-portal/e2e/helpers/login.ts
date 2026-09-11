import { expect, type Page } from "@playwright/test";

/**
 * Credentials for the real `opsmind` Keycloak realm. `test.agent` is the
 * plain EMPLOYEE-defaulted user opsmind-realm.json ships for exactly this
 * purpose (see the realm file's own employee-test-client comment).
 */
export const EMPLOYEE_USERNAME = process.env.E2E_EMPLOYEE_USERNAME ?? "test.agent";
export const EMPLOYEE_PASSWORD = process.env.E2E_EMPLOYEE_PASSWORD ?? "test-password";

/**
 * Drives the real login: app -> inline username/password form (the BFF's
 * own `PasswordLoginController` direct-grant login — no navigation to
 * Keycloak's own hosted page) -> the composer, once `AuthGate` sees an
 * `authenticated` status carrying no support role.
 *
 * Leaves `page` on the authenticated home view.
 */
export async function loginAsEmployee(page: Page): Promise<void> {
  await page.goto("/");

  // AuthGate briefly shows "Checking your session…" then the login screen.
  await expect(page.getByLabel(/username/i)).toBeVisible();
  await page.getByLabel(/username/i).fill(EMPLOYEE_USERNAME);
  await page.getByLabel(/password/i).fill(EMPLOYEE_PASSWORD);
  await page.getByRole("button", { name: /^sign in$/i }).click();

  // Back on the app, authenticated: the message composer is the stable
  // post-login landmark (SPEC-EP-005).
  await expect(page.getByPlaceholder("Describe your issue…")).toBeVisible({ timeout: 20_000 });
}
