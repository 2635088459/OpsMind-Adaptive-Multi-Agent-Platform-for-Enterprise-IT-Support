import { defineConfig, devices } from "@playwright/test";

/**
 * Real browser-driven E2E for the support console. Same shape as the
 * employee-portal config: unmocked, against the real local platform, with a
 * one-time real Keycloak login stored in e2e/.auth/support.json.
 *
 *   docker compose -f infrastructure/docker-compose/local-platform.yml \
 *                  -f infrastructure/docker-compose/full-platform.yml up -d
 *   # /etc/hosts must contain `127.0.0.1 keycloak`
 *
 * `pnpm --filter support-console test:e2e`
 *
 * Logs in as `support.agent` (realm role support_agent). The support-console
 * Keycloak client is standard-flow only, so this is a genuine browser
 * Authorization-Code login, not a password grant.
 */
const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:5174";

export default defineConfig({
  testDir: "./e2e",
  outputDir: "./e2e/.output",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [["github"], ["list"]] : [["list"]],
  timeout: 60_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    ignoreHTTPSErrors: true,
  },

  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        storageState: "e2e/.auth/support.json",
      },
      dependencies: ["setup"],
    },
  ],

  webServer: {
    command: "pnpm dev",
    url: BASE_URL,
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
