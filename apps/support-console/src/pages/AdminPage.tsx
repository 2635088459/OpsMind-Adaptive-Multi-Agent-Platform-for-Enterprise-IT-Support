import { NavLink, Outlet } from "react-router";
import { useAuthStore } from "@/store/authStore";
import { isSupportAdmin } from "@/features/auth/roles";

const TABS = [
  { to: "knowledge", label: "Knowledge base" },
  { to: "connectors", label: "Connectors" },
  { to: "policy", label: "Policy" },
] as const;

/**
 * The operator surfaces that previously only existed as `curl` commands:
 * knowledge-document ingest (memory-knowledge-service), connector
 * registration/health (tool-integration-gateway), and policy drafting
 * (policy-approval-governance-service). Admin-only — a non-admin sees the
 * "not authorised" note, never the forms.
 */
export function AdminPage() {
  const roles = useAuthStore((state) => state.roles);

  if (!isSupportAdmin(roles)) {
    return (
      <div className="rounded-xl border border-border bg-surface p-8 text-sm text-ink-muted" data-testid="admin-forbidden">
        You need the <code>support_admin</code> role to view the admin console.
      </div>
    );
  }

  const tabClass = ({ isActive }: { isActive: boolean }) =>
    `border-b-2 px-1 pb-2 text-sm font-medium ${isActive ? "border-ink text-ink" : "border-transparent text-ink-muted hover:text-ink"}`;

  return (
    <div>
      <h1 className="text-xl font-semibold text-ink">Admin</h1>
      <nav className="mt-4 flex gap-6 border-b border-border">
        {TABS.map((tab) => (
          <NavLink key={tab.to} to={tab.to} className={tabClass}>
            {tab.label}
          </NavLink>
        ))}
      </nav>
      <div className="mt-6">
        <Outlet />
      </div>
    </div>
  );
}
