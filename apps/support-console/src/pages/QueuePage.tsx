import { useAuthStore } from "@/store/authStore";
import { decodeJwtPayload } from "@/lib/jwt";
import { QueueTable } from "@/features/queue/QueueTable";

/** SPEC-SC-001 §9 "land on the queue view" + SPEC-SC-003/004/005's own real content. */
export function QueuePage() {
  const accessToken = useAuthStore((state) => state.accessToken);
  const roles = useAuthStore((state) => state.roles);
  const claims = accessToken ? decodeJwtPayload(accessToken) : null;
  const displayName = typeof claims?.preferred_username === "string" ? claims.preferred_username : "there";

  return (
    <div className="mx-auto max-w-5xl px-6 py-10">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-ink">OpsMind Support Console</h1>
        <p className="text-sm text-ink-muted">
          {displayName} · {roles.join(", ") || "no roles"}
        </p>
      </div>
      <div className="mt-6">
        <QueueTable filters={{}} />
      </div>
    </div>
  );
}
