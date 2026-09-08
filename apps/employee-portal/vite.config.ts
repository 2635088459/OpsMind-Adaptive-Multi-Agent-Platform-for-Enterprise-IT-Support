/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

// Frozen technology-baseline: React 19 + TypeScript + Vite 8.x, Tailwind CSS,
// Vitest + React Testing Library. Port 5173 is fixed (not left to Vite's
// auto-increment-on-conflict default) because user-access-authentication-
// service's own CORS allow-list and its `success-redirect-uri` are both
// pinned to this exact origin (application-local.yml, BrowserCorsProperties)
// — see that service's SecurityConfig/BrowserSessionTokenController for the
// real BFF endpoint this app's login flow depends on. VITE_BFF_BASE_URL
// itself is read at runtime via import.meta.env (src/lib/env.ts) — Vite's
// own built-in .env handling, no custom `define` needed.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
  test: {
    // Unit/component tests only. The Playwright E2E specs under e2e/ import
    // @playwright/test and must never be collected by Vitest's default glob.
    include: ["src/**/*.test.{ts,tsx}"],
    environment: "jsdom",
    globals: false,
    setupFiles: ["./src/test/setup.ts"],
    css: true,
  },
});
