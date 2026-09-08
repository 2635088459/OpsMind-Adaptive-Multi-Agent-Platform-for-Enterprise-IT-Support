import { test as setup } from "@playwright/test";
import { loginAsSupportAgent } from "./helpers/login";

const AUTH_FILE = "e2e/.auth/support.json";

setup("authenticate as a support agent", async ({ page }) => {
  await loginAsSupportAgent(page);
  await page.context().storageState({ path: AUTH_FILE });
});
