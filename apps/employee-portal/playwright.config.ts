import { defineConfig, devices } from "@playwright/test";

/**
 * Real browser-driven E2E for the employee portal. Unlike the Vitest suite
 * (which mocks every network call with MSW), these run the app unmocked
 * against the real local platform:
 *
 *   docker compose -f infrastructure/docker-compose/local-platform.yml \
 *                  -f infrastructure/docker-compose/full-platform.yml up -d
 *   # /etc/hosts must contain `127.0.0.1 keycloak` (real browser has to
 *   # resolve the same hostname the BFF's issuer claim uses)
 *
 * `pnpm --filter employee-portal test:e2e`
 *
 * The `setup` project performs the real Keycloak Authorization-Code + PKCE
 * login once (through user-access-authentication-service's BFF) and stores
 * the resulting OPSMIND_SESSION cookie in e2e/.auth/employee.json; every
 * other spec reuses it via `storageState` instead of logging in again.
 */
const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:5173";

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
    // The BFF issues OPSMIND_SESSION as SameSite=None; a real browser context
    // is required for the cross-origin (5173 -> 8087 -> keycloak:8080) dance.
    ignoreHTTPSErrors: true,
  },

  projects: [
    {
      name: "setup",
      testMatch: /auth\.setup\.ts/,
    },
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        storageState: "e2e/.auth/employee.json",
      },
      dependencies: ["setup"],
    },
  ],

  // The dev server is expected to already be running (the docker stack has
  // to be up anyway); reuse it rather than racing a second `vite` on :5173.
  webServer: {
    command: "pnpm dev",
    url: BASE_URL,
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
