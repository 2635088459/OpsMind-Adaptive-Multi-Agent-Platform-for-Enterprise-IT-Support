import { useState, type FormEvent } from "react";
import { useAuthStore } from "@/store/authStore";

/**
 * Real inline username/password sign-in — a direct-grant login the BFF's
 * own `PasswordLoginController` runs server-side against this app's own
 * `support-console` Keycloak client registration. Most users arrive here
 * already authenticated (handed off from the employee-portal's own unified
 * front door, which re-verifies the same credentials against this exact
 * registration before navigating); this form is what a direct/bookmarked
 * visit — or the front door's own rare already-authenticated-elsewhere
 * fallback — actually uses to establish this app's own session.
 */
export function LoginPage() {
  const loginWithPassword = useAuthStore((state) => state.loginWithPassword);
  const status = useAuthStore((state) => state.status);
  const error = useAuthStore((state) => state.error);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  const submitting = status === "login_in_progress";

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!username.trim() || !password || submitting) return;
    void loginWithPassword(username.trim(), password);
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm rounded-xl border border-border bg-surface p-8 shadow-sm">
        <h1 className="text-xl font-semibold text-ink">OpsMind Support Console</h1>
        <p className="mt-1 text-sm text-ink-muted">
          {status === "session_expired"
            ? "Your session has ended. Sign in again to continue."
            : "Sign in with your support agent/admin account."}
        </p>

        {error ? (
          <p role="alert" className="mt-4 rounded-md bg-danger/10 px-3 py-2 text-sm text-danger">
            {error}
          </p>
        ) : null}

        <form onSubmit={handleSubmit} className="mt-6 space-y-3">
          <div>
            <label htmlFor="login-username" className="mb-1 block text-xs font-medium text-ink-muted">
              Username
            </label>
            <input
              id="login-username"
              name="username"
              type="text"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={submitting}
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink outline-none focus:border-brand-600 disabled:opacity-60"
            />
          </div>
          <div>
            <label htmlFor="login-password" className="mb-1 block text-xs font-medium text-ink-muted">
              Password
            </label>
            <input
              id="login-password"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={submitting}
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink outline-none focus:border-brand-600 disabled:opacity-60"
            />
          </div>
          <button
            type="submit"
            disabled={submitting || !username.trim() || !password}
            className="mt-1 w-full rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {submitting ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </div>
    </div>
  );
}
