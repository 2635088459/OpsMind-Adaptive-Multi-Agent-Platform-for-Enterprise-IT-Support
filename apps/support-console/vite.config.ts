/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

// Frozen technology-baseline: React 19 + TypeScript + Vite 8.x, Tailwind CSS,
// Vitest + React Testing Library. Port 5174 is fixed (not left to Vite's
// auto-increment-on-conflict default) because every backend service's own
// CORS allow-list and user-access-authentication-service's own
// support-console-success-redirect-uri are both pinned to this exact origin
// (application-local.yml) — see SecurityConfig/BrowserSessionTokenController
// for the real BFF endpoint this app's login flow depends on, and
// SPEC-SC-001's own real "support-console" Keycloak client registration.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
    },
  },
  server: {
    port: 5174,
    strictPort: true,
  },
  test: {
    environment: "jsdom",
    globals: false,
    setupFiles: ["./src/test/setup.ts"],
    css: true,
  },
});
