/**
 * The one place this app reads `VITE_BFF_BASE_URL` — user-access-authentication-
 * service's own real origin (SecurityConfig#browserLoginFilterChain,
 * BrowserSessionTokenController). A missing value defaults to that service's
 * own local-dev port (8087, application-local.yml) rather than throwing, so a
 * fresh checkout without a `.env` still runs against the platform's own
 * documented local-dev defaults.
 */
export const BFF_BASE_URL: string = import.meta.env.VITE_BFF_BASE_URL ?? "http://localhost:8087";
