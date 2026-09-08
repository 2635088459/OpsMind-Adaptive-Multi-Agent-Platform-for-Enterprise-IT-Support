import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  draftPolicy,
  getPolicy,
  listPolicies,
  publishPolicyVersion,
  reviewPolicyVersion,
  type DraftPolicyInput,
} from "@/features/admin/policy/api";

const RULES_PLACEHOLDER = `[
  {
    "ruleId": "require-approval-high-risk",
    "effect": "REQUIRE_APPROVAL",
    "match": { "riskLevel": "HIGH" }
  }
]`;

const POLICIES_KEY = ["admin", "policies"] as const;

/**
 * Policy admin: list existing policies, drill into a policy's version history
 * (draft → review → publish lifecycle, SPEC-PG-018), and draft a new version.
 * `rules` is entered as JSON — the service validates it. Requires the
 * `policy:read` / `policy:draft` / `policy:review` / `policy:publish` scopes.
 */
export function PolicyAdmin() {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const policies = useQuery({ queryKey: POLICIES_KEY, queryFn: listPolicies });
  const detail = useQuery({
    queryKey: ["admin", "policy", selectedId],
    queryFn: () => getPolicy(selectedId as string),
    enabled: selectedId !== null,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: POLICIES_KEY });
    if (selectedId) queryClient.invalidateQueries({ queryKey: ["admin", "policy", selectedId] });
  };
  const review = useMutation({ mutationFn: reviewPolicyVersion, onSuccess: invalidate });
  const publish = useMutation({ mutationFn: publishPolicyVersion, onSuccess: invalidate });

  return (
    <div className="grid gap-8 lg:grid-cols-[260px_1fr]">
      <section>
        <h2 className="text-sm font-semibold text-ink">Policies</h2>
        {policies.isLoading ? (
          <p className="mt-2 text-sm text-ink-muted">Loading…</p>
        ) : policies.isError ? (
          <p className="mt-2 text-sm text-danger" data-testid="policies-error">
            Could not load policies: {(policies.error as Error).message}
          </p>
        ) : (
          <ul className="mt-2 space-y-1" data-testid="policies-list">
            {(policies.data ?? []).map((p) => (
              <li key={p.policyId}>
                <button
                  type="button"
                  onClick={() => setSelectedId(p.policyId)}
                  className={`w-full rounded-md px-3 py-2 text-left text-sm ${selectedId === p.policyId ? "bg-surface-muted text-ink" : "text-ink-muted hover:bg-surface-muted"}`}
                >
                  <span className="font-medium text-ink">{p.policyName}</span>
                  <span className="block text-xs">
                    {p.scope} · {p.status}
                    {p.currentPublishedVersion !== null ? ` · v${p.currentPublishedVersion} live` : ""}
                  </span>
                </button>
              </li>
            ))}
            {(policies.data ?? []).length === 0 ? <li className="px-3 py-2 text-sm text-ink-muted">No policies yet.</li> : null}
          </ul>
        )}
      </section>

      <div className="space-y-8">
        {selectedId && detail.data ? (
          <section data-testid="policy-detail">
            <h2 className="text-sm font-semibold text-ink">
              {detail.data.policy.policyName} — versions
            </h2>
            {review.isError || publish.isError ? (
              <p className="mt-2 rounded-md border border-danger/30 bg-danger/5 px-3 py-2 text-sm text-danger" data-testid="lifecycle-error">
                {((review.error ?? publish.error) as Error).message}
              </p>
            ) : null}
            <div className="mt-2 overflow-x-auto rounded-xl border border-border bg-surface">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-border text-xs uppercase tracking-wide text-ink-muted">
                  <tr>
                    <th className="px-4 py-2">Version</th>
                    <th className="px-4 py-2">Status</th>
                    <th className="px-4 py-2">Author</th>
                    <th className="px-4 py-2">Reviewer</th>
                    <th className="px-4 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {detail.data.versions.map((v) => (
                    <tr key={v.policyVersionId} className="border-b border-border last:border-0" data-testid="policy-version-row">
                      <td className="px-4 py-2 font-mono">v{v.versionNumber}</td>
                      <td className="px-4 py-2 text-ink-muted">{v.status}</td>
                      <td className="px-4 py-2 text-ink-muted">{v.createdBy}</td>
                      <td className="px-4 py-2 text-ink-muted">{v.reviewedBy ?? "—"}</td>
                      <td className="px-4 py-2 text-right">
                        {v.status === "DRAFT" ? (
                          <button
                            type="button"
                            disabled={review.isPending}
                            onClick={() => review.mutate(v.policyVersionId)}
                            className="rounded-md border border-border px-2 py-1 text-xs font-medium hover:bg-surface-muted"
                          >
                            Mark reviewed
                          </button>
                        ) : v.status === "REVIEWING" ? (
                          <button
                            type="button"
                            disabled={publish.isPending}
                            onClick={() => publish.mutate(v.policyVersionId)}
                            className="rounded-md bg-ink px-2 py-1 text-xs font-medium text-surface hover:opacity-90"
                          >
                            Publish
                          </button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        ) : null}

        <DraftPolicyForm onDrafted={invalidate} />
      </div>
    </div>
  );
}

function DraftPolicyForm({ onDrafted }: { onDrafted: () => void }) {
  const [policyId, setPolicyId] = useState("");
  const [policyName, setPolicyName] = useState("");
  const [scope, setScope] = useState("");
  const [rulesText, setRulesText] = useState(RULES_PLACEHOLDER);
  const [parseError, setParseError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (input: DraftPolicyInput) => draftPolicy(input),
    onSuccess: onDrafted,
  });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setParseError(null);
    let rules: unknown[];
    try {
      const parsed = JSON.parse(rulesText);
      if (!Array.isArray(parsed)) throw new Error("rules must be a JSON array");
      rules = parsed;
    } catch (error) {
      setParseError((error as Error).message);
      return;
    }
    mutation.mutate({ policyId: policyId.trim() || undefined, policyName: policyName.trim(), scope: scope.trim(), rules });
  };

  const input = "mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-ink";
  const label = "block text-sm font-medium text-ink";
  const canSubmit = policyName.trim() && scope.trim() && !mutation.isPending;

  return (
    <form onSubmit={submit} className="max-w-2xl" noValidate>
      <h2 className="text-sm font-semibold text-ink">Draft a policy version</h2>
      {mutation.isSuccess ? (
        <p className="mt-2 rounded-md border border-border bg-surface-muted px-3 py-2 text-sm text-ink" data-testid="draft-success">
          Drafted policy <span className="font-mono">{mutation.data.policyId}</span> version {mutation.data.versionNumber} ({mutation.data.status}).
        </p>
      ) : null}
      {parseError ? (
        <p className="mt-2 rounded-md border border-danger/30 bg-danger/5 px-3 py-2 text-sm text-danger" data-testid="rules-parse-error">
          Rules JSON is invalid: {parseError}
        </p>
      ) : null}
      {mutation.isError ? (
        <p className="mt-2 rounded-md border border-danger/30 bg-danger/5 px-3 py-2 text-sm text-danger" data-testid="draft-error">
          {(mutation.error as Error).message}
        </p>
      ) : null}

      <div className="mt-2 grid grid-cols-2 gap-4">
        <div>
          <label className={label} htmlFor="p-id">Policy id (blank = new)</label>
          <input id="p-id" className={input} value={policyId} onChange={(e) => setPolicyId(e.target.value)} />
        </div>
        <div>
          <label className={label} htmlFor="p-name">Policy name</label>
          <input id="p-name" className={input} value={policyName} onChange={(e) => setPolicyName(e.target.value)} />
        </div>
      </div>
      <div className="mt-4">
        <label className={label} htmlFor="p-scope">Scope</label>
        <input id="p-scope" className={input} value={scope} onChange={(e) => setScope(e.target.value)} placeholder="tool-execution" />
      </div>
      <div className="mt-4">
        <label className={label} htmlFor="p-rules">Rules (JSON array)</label>
        <textarea id="p-rules" rows={10} className={`${input} font-mono`} value={rulesText} onChange={(e) => setRulesText(e.target.value)} />
      </div>
      <button
        type="submit"
        disabled={!canSubmit}
        className="mt-5 rounded-md bg-ink px-4 py-2 text-sm font-medium text-surface disabled:cursor-not-allowed disabled:opacity-60"
      >
        {mutation.isPending ? "Drafting…" : "Draft policy version"}
      </button>
    </form>
  );
}
