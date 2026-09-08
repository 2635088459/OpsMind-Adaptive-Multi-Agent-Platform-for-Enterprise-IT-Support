import { test as setup } from "@playwright/test";
import { loginAsEmployee } from "./helpers/login";

const AUTH_FILE = "e2e/.auth/employee.json";

/**
 * Runs once before the `chromium` project. Performs the real Keycloak login
 * and persists the browser storage (the HttpOnly OPSMIND_SESSION cookie) so
 * every spec starts already authenticated.
 */
setup("authenticate as an employee", async ({ page }) => {
  await loginAsEmployee(page);
  await page.context().storageState({ path: AUTH_FILE });
});
