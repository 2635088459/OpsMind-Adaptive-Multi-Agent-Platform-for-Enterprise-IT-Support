import { useAuthStore } from "@/store/authStore";

export function LoginPage() {
  const login = useAuthStore((state) => state.login);
  const status = useAuthStore((state) => state.status);
  const error = useAuthStore((state) => state.error);

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-muted px-4">
      <div className="w-full max-w-sm rounded-2xl border border-border bg-surface p-8 shadow-sm">
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
            : "Sign in to reach the employee support portal."}
        </p>

        {error ? (
          <p role="alert" className="mt-4 rounded-md bg-danger/10 px-3 py-2 text-sm text-danger">
            {error}
          </p>
        ) : null}

        <button
          type="button"
          onClick={login}
          disabled={status === "login_in_progress"}
          className="mt-6 w-full rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {status === "login_in_progress" ? "Redirecting to sign-in…" : "Sign in with company account"}
        </button>
      </div>
    </div>
  );
}
