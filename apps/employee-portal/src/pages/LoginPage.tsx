import { useAuthStore } from "@/store/authStore";

export function LoginPage() {
  const login = useAuthStore((state) => state.login);
  const status = useAuthStore((state) => state.status);
  const error = useAuthStore((state) => state.error);

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm rounded-xl border border-border bg-surface p-8 shadow-sm">
        <h1 className="text-xl font-semibold text-ink">OpsMind</h1>
        <p className="mt-1 text-sm text-ink-muted">Sign in to reach the employee support portal.</p>

        {error ? (
          <p role="alert" className="mt-4 rounded-md bg-danger/10 px-3 py-2 text-sm text-danger">
            {error}
          </p>
        ) : null}

        <button
          type="button"
          onClick={login}
          disabled={status === "login_in_progress"}
          className="mt-6 w-full rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {status === "login_in_progress" ? "Redirecting to sign-in…" : "Sign in with company account"}
        </button>
      </div>
    </div>
  );
}
