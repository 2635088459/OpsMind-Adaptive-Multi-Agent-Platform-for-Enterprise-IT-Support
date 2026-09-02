import { useAuthStore } from "@/store/authStore";
import { decodeJwtPayload } from "@/lib/jwt";

/**
 * SPEC-EP-001 §9's own "redirect to the portal's home route" — deliberately
 * just a real `AUTHENTICATED` landing confirmation, not the dashboard itself
 * (that content belongs to this domain's later specs, not this login spec).
 */
export function HomePage() {
  const accessToken = useAuthStore((state) => state.accessToken);
  const claims = accessToken ? decodeJwtPayload(accessToken) : null;
  const displayName = typeof claims?.preferred_username === "string" ? claims.preferred_username : "there";

  return (
    <div className="mx-auto max-w-2xl px-6 py-16">
      <h1 className="text-2xl font-semibold text-ink">Welcome, {displayName}.</h1>
      <p className="mt-2 text-sm text-ink-muted">
        You are signed in. This landing page will grow into the real employee support portal as later specs land.
      </p>
    </div>
  );
}
