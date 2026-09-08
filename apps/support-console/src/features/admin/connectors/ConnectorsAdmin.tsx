import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuthStore } from "@/store/authStore";
import { decodeJwtPayload } from "@/lib/jwt";
import {
  listConnectors,
  registerConnector,
  updateConnectorStatus,
  type RegisterConnectorInput,
} from "@/features/admin/connectors/api";

const RISK_LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
const QUERY_KEY = ["admin", "connectors"] as const;

export function ConnectorsAdmin() {
  const queryClient = useQueryClient();
  const accessToken = useAuthStore((state) => state.accessToken);
  const actor =
    (accessToken && (decodeJwtPayload(accessToken)?.preferred_username as string | undefined)) || "support-console";

  const connectors = useQuery({ queryKey: QUERY_KEY, queryFn: listConnectors });

  const register = useMutation({
    mutationFn: (input: RegisterConnectorInput) => registerConnector(input),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: QUERY_KEY }),
  });
  const changeStatus = useMutation({
    mutationFn: ({ id, action }: { id: string; action: "enable" | "disable" | "deprecate" }) =>
      updateConnectorStatus(id, action, actor),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: QUERY_KEY }),
  });

  const [form, setForm] = useState<RegisterConnectorInput>({
    name: "",
    version: "1.0.0",
    capabilityNames: [],
    riskLevel: "MEDIUM",
    requiresApproval: true,
    isMutating: true,
    allowedHosts: [],
  });
  const [capsText, setCapsText] = useState("");
  const [hostsText, setHostsText] = useState("");

  const set = <K extends keyof RegisterConnectorInput>(key: K, value: RegisterConnectorInput[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  const caps = capsText.split(",").map((c) => c.trim()).filter(Boolean);
  const canSubmit = form.name.trim() && form.version.trim() && caps.length > 0 && !register.isPending;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    register.mutate({
      ...form,
      capabilityNames: caps,
      allowedHosts: hostsText.split(",").map((h) => h.trim()).filter(Boolean),
    });
  };

  const input = "mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink";
  const label = "block text-sm font-medium text-ink";

  return (
    <div className="space-y-8">
      <section>
        <h2 className="text-sm font-semibold text-ink">Registered connectors</h2>
        {connectors.isLoading ? (
          <p className="mt-2 text-sm text-ink-muted">Loading…</p>
        ) : connectors.isError ? (
          <p className="mt-2 text-sm text-danger" data-testid="connectors-error">Could not load connectors.</p>
        ) : (
          <div className="mt-2 overflow-x-auto rounded-xl border border-border bg-surface" data-testid="connectors-table">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border text-xs uppercase tracking-wide text-ink-muted">
                <tr>
                  <th className="px-4 py-2">Name</th>
                  <th className="px-4 py-2">Capabilities</th>
                  <th className="px-4 py-2">Risk</th>
                  <th className="px-4 py-2">Health</th>
                  <th className="px-4 py-2" />
                </tr>
              </thead>
              <tbody>
                {(connectors.data ?? []).map((c) => (
                  <tr key={c.connectorId} className="border-b border-border last:border-0" data-testid="connector-row">
                    <td className="px-4 py-2">
                      <div className="font-medium text-ink">{c.name}</div>
                      <div className="text-xs text-ink-muted">v{c.version} · {c.sideEffectKind}</div>
                    </td>
                    <td className="px-4 py-2 text-ink-muted">{c.capabilities.join(", ")}</td>
                    <td className="px-4 py-2 text-ink-muted">{c.riskLevel}{c.requiresApproval ? " · approval" : ""}</td>
                    <td className="px-4 py-2 text-ink-muted">{c.healthStatus}</td>
                    <td className="px-4 py-2 text-right">
                      <select
                        aria-label={`Change status of ${c.name}`}
                        className="rounded-md border border-border bg-surface px-2 py-1 text-xs"
                        defaultValue=""
                        onChange={(e) => {
                          if (e.target.value) {
                            changeStatus.mutate({ id: c.connectorId, action: e.target.value as "enable" | "disable" | "deprecate" });
                            e.target.value = "";
                          }
                        }}
                      >
                        <option value="">Action…</option>
                        <option value="enable">Enable</option>
                        <option value="disable">Disable</option>
                        <option value="deprecate">Deprecate</option>
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section>
        <h2 className="text-sm font-semibold text-ink">Register a connector</h2>
        <form onSubmit={submit} className="mt-2 max-w-2xl" noValidate>
          {register.isError ? (
            <p className="mb-4 rounded-md border border-danger/30 bg-danger/5 px-3 py-2 text-sm text-danger" data-testid="register-error">
              {(register.error as Error).message}
            </p>
          ) : null}
          {register.isSuccess ? (
            <p className="mb-4 rounded-md border border-border bg-surface-muted px-3 py-2 text-sm text-ink" data-testid="register-success">
              Registered {register.data.name} ({register.data.healthStatus}).
            </p>
          ) : null}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className={label} htmlFor="c-name">Name</label>
              <input id="c-name" className={input} value={form.name} onChange={(e) => set("name", e.target.value)} />
            </div>
            <div>
              <label className={label} htmlFor="c-version">Version</label>
              <input id="c-version" className={input} value={form.version} onChange={(e) => set("version", e.target.value)} />
            </div>
          </div>
          <div className="mt-4">
            <label className={label} htmlFor="c-caps">Capabilities (comma-separated)</label>
            <input id="c-caps" className={input} value={capsText} onChange={(e) => setCapsText(e.target.value)} placeholder="identity.user.unlock" />
          </div>
          <div className="mt-4 grid grid-cols-2 gap-4">
            <div>
              <label className={label} htmlFor="c-risk">Risk level</label>
              <select id="c-risk" className={input} value={form.riskLevel} onChange={(e) => set("riskLevel", e.target.value)}>
                {RISK_LEVELS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <div>
              <label className={label} htmlFor="c-hosts">Allowed hosts (comma-separated)</label>
              <input id="c-hosts" className={input} value={hostsText} onChange={(e) => setHostsText(e.target.value)} placeholder="keycloak" />
            </div>
          </div>
          <div className="mt-3 flex gap-6 text-sm text-ink">
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={form.isMutating} onChange={(e) => set("isMutating", e.target.checked)} />
              Mutating
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={form.requiresApproval} onChange={(e) => set("requiresApproval", e.target.checked)} />
              Requires approval
            </label>
          </div>
          <button
            type="submit"
            disabled={!canSubmit}
            className="mt-5 rounded-md bg-ink px-4 py-2 text-sm font-medium text-surface disabled:cursor-not-allowed disabled:opacity-60"
          >
            {register.isPending ? "Registering…" : "Register connector"}
          </button>
        </form>
      </section>
    </div>
  );
}
