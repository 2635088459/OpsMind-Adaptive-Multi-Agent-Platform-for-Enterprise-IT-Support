/** SPEC-SC-013: the one shared conflict notice every mutation form (triage/assign/status-transition) renders on a real 412. */
export function VersionConflictBanner({ currentVersion, onReload }: { currentVersion: number | null; onReload: () => void }) {
  return (
    <div className="rounded-md bg-surface-muted px-3 py-2 text-sm text-ink" data-testid="version-conflict">
      <p>
        This ticket changed since you loaded it{currentVersion !== null ? ` (now at version ${currentVersion})` : ""}. Reload before trying again.
      </p>
      <button
        type="button"
        onClick={onReload}
        className="mt-1 rounded-md border border-border bg-surface px-2 py-1 text-xs font-medium hover:bg-surface-muted"
      >
        Reload
      </button>
    </div>
  );
}
