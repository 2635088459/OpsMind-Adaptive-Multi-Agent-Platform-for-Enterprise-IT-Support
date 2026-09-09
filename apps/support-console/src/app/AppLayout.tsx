import { useEffect } from "react";
import { NavLink, Outlet } from "react-router";
import { useAuthStore } from "@/store/authStore";
import { decodeJwtPayload } from "@/lib/jwt";
import { isSupportAdmin } from "@/features/auth/roles";
import { LoginPage } from "@/pages/LoginPage";

/**
 * The console's session gate + top-level chrome. Same `03-state-machine`
 * pattern as the old `AuthGate` (which this replaces) — renders purely off
 * `AuthStatus` — but now wraps a real react-router `<Outlet/>` so the queue,
 * a ticket detail view, and the admin section are genuine routes rather than
 * one state-switched page.
 */
export function AppLayout() {
  const status = useAuthStore((state) => state.status);
  const checkSession = useAuthStore((state) => state.checkSession);
  const accessToken = useAuthStore((state) => state.accessToken);
  const roles = useAuthStore((state) => state.roles);

  useEffect(() => {
    void checkSession();
  }, [checkSession]);

  if (status === "checking") {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <p className="text-sm text-ink-muted">Checking your session…</p>
      </div>
    );
  }

  if (status !== "authenticated" && status !== "token_refreshing") {
    return <LoginPage />;
  }

  const claims = accessToken ? decodeJwtPayload(accessToken) : null;
  const displayName = typeof claims?.preferred_username === "string" ? claims.preferred_username : "there";
  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? "bg-surface-muted text-ink" : "text-ink-muted hover:bg-surface-muted"}`;

  return (
    <div className="min-h-screen">
      <header className="border-b border-border bg-surface">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-3">
          <div className="flex items-center gap-5">
            <span className="text-base font-semibold text-ink">OpsMind Support Console</span>
            <nav className="flex items-center gap-1">
              <NavLink to="/" end className={linkClass}>
                Queue
              </NavLink>
              <NavLink to="/observability" className={linkClass}>
                Observability
              </NavLink>
              {isSupportAdmin(roles) ? (
                <NavLink to="/admin" className={linkClass}>
                  Admin
                </NavLink>
              ) : null}
            </nav>
          </div>
          <p className="text-sm text-ink-muted">
            {displayName} · {roles.join(", ") || "no roles"}
          </p>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">
        <Outlet />
      </main>
    </div>
  );
}
