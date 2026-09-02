import type { ReactNode } from "react";
import { useAuthStore } from "@/store/authStore";
import { isSupportAdmin } from "@/features/auth/roles";

/**
 * SPEC-SC-002: wraps an admin-only affordance (e.g. SPEC-SC-011's own
 * cross-team reassignment control). Renders nothing at all for a non-admin
 * — never a disabled/greyed-out version of the control, since this domain's
 * own honest framing is "hide", not "show but block" (the backend itself
 * enforces nothing extra here; showing a disabled button would falsely
 * imply a permission boundary the backend doesn't actually have — SPEC-SC-002
 * §6's own explicit non-goal).
 */
export function AdminOnly({ children }: { children: ReactNode }) {
  const roles = useAuthStore((state) => state.roles);
  if (!isSupportAdmin(roles)) return null;
  return <>{children}</>;
}
