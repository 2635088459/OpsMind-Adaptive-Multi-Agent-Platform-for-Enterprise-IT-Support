import { useState, type FormEvent } from "react";
import { useAuthStore } from "@/store/authStore";

/**
 * The single sign-in front door for all three OpsMind personas. Everyone
 * signs in here with a real inline username/password form (a direct-grant
 * login the BFF's own `PasswordLoginController` runs server-side — no
 * navigation to Keycloak's own hosted page, which used to look nothing like
 * the rest of this app); `useAuthStore#loginWithPassword` keeps an employee
 * on this app and hands a user whose token carries `support_agent` /
 * `support_admin` off to the Support Console (re-authenticating with the
 * same credentials against its own, differently-scoped Keycloak client —
 * least privilege, never this app's own token).
 */

const CHANNELS: { name: string; blurb: string }[] = [
  { name: "Employee support", blurb: "Chat with the assistant, open and track your tickets." },
  { name: "IT staff", blurb: "The support queue — triage, assign, resolve." },
  { name: "IT supervisor", blurb: "Everything IT staff can do, plus knowledge / connectors / policy admin." },
];

// Local/demo accounts. The supervisor account is intentionally not listed here.
const DEMO_ACCOUNTS: { role: string; username: string; password: string }[] = [
  { role: "Employee", username: "test.agent", password: "test-password" },
  { role: "IT staff", username: "support.agent", password: "test-password" },
];

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

  function fillDemoAccount(account: (typeof DEMO_ACCOUNTS)[number]) {
    setUsername(account.username);
    setPassword(account.password);
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-muted px-4 py-10">
      <div className="w-full max-w-md rounded-2xl border border-border bg-surface p-8 shadow-sm">
        <div className="flex items-center gap-2.5">
          <div className="flex size-8 shrink-0 items-center justify-center rounded-[9px] bg-brand-600 text-sm font-extrabold tracking-tight text-white">
            OM
          </div>
          <span className="text-base font-bold tracking-tight text-ink">OpsMind</span>
          <span className="border-l border-border pl-2 text-xs text-faint">IT Support</span>
        </div>

        <p className="mt-6 text-sm text-ink-muted">
          {status === "session_expired"
            ? "Your session has ended. Sign in again to continue."
            : "Sign in once — you'll land in the right place for your role."}
        </p>

        <ul aria-label="Support channels" className="mt-4 space-y-2">
          {CHANNELS.map((c) => (
            <li key={c.name} className="rounded-lg border border-border bg-surface-muted px-3 py-2">
              <p className="text-sm font-semibold text-ink">{c.name}</p>
              <p className="text-xs text-ink-muted">{c.blurb}</p>
            </li>
          ))}
        </ul>

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
              className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-ink outline-none focus:border-brand-600 disabled:opacity-60"
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
              className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-ink outline-none focus:border-brand-600 disabled:opacity-60"
            />
          </div>
          <button
            type="submit"
            disabled={submitting || !username.trim() || !password}
            className="mt-1 w-full rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {submitting ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <div className="mt-6 rounded-lg border border-dashed border-border px-3 py-3">
          <p className="text-xs font-semibold uppercase tracking-wide text-faint">Demo accounts</p>
          <dl className="mt-2 space-y-1.5">
            {DEMO_ACCOUNTS.map((a) => (
              <div key={a.username} className="flex items-center justify-between gap-3 text-xs">
                <div>
                  <dt className="inline text-ink-muted">{a.role}: </dt>
                  <dd className="inline font-mono text-ink">
                    {a.username} / {a.password}
                  </dd>
                </div>
                <button
                  type="button"
                  onClick={() => fillDemoAccount(a)}
                  disabled={submitting}
                  className="shrink-0 rounded-md border border-border px-2 py-1 text-xs font-medium text-ink-muted hover:bg-surface-muted disabled:opacity-60"
                >
                  Use
                </button>
              </div>
            ))}
          </dl>
        </div>
      </div>
    </div>
  );
}
