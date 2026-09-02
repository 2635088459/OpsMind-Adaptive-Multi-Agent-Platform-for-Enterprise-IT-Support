import { useAuthStore } from "@/store/authStore";
import { newTraceparent } from "@/lib/trace";
import { parseApiError } from "@/lib/apiError";

export interface AuthedRequestInit extends Omit<RequestInit, "headers"> {
  headers?: Record<string, string>;
  /** Set for every side-effecting call — a fresh call site must generate a new one; a retry of the same attempt reuses it. */
  idempotencyKey?: string;
}

/**
 * The one place every real backend call in this app goes through: attaches
 * the real Keycloak access token this app's own BFF relayed (never a token
 * this app minted itself), a `traceparent`, and an `Idempotency-Key` when the
 * caller supplies one. Parses the shared `{error:{...}}` envelope on failure
 * into a typed `ApiError` (src/lib/apiError.ts) rather than a raw `Response`.
 *
 * Deliberately reads `useAuthStore.getState()` directly rather than taking
 * the token as a parameter — every call site would otherwise have to thread
 * it through, and the token's only real source of truth is this one store.
 *
 * SPEC-SC-020: a caller-supplied `traceparent` in `init.headers` wins over
 * generating a fresh one — every call site keeps getting a fresh root span
 * by default (the common case, unchanged), but `useAiLog`'s own 3 concurrent
 * SPEC-SC-006 calls need to share one common parent span rather than
 * producing 3 disconnected traces, which is only possible if this function
 * can be told "use this one instead."
 */
export async function authedFetch(url: string, init: AuthedRequestInit = {}): Promise<Response> {
  const { accessToken } = useAuthStore.getState();
  if (!accessToken) {
    throw new Error("authedFetch called with no access token — the caller must ensure AuthStatus is 'authenticated' first");
  }

  const headers: Record<string, string> = {
    ...init.headers,
    Authorization: `Bearer ${accessToken}`,
    traceparent: init.headers?.traceparent ?? newTraceparent(),
  };
  if (init.idempotencyKey) {
    headers["Idempotency-Key"] = init.idempotencyKey;
  }

  const response = await fetch(url, { ...init, headers });
  if (!response.ok) {
    throw await parseApiError(response);
  }
  return response;
}

/** A fresh idempotency key for one logical attempt — a retry of the SAME attempt must reuse the value this returned, not call this again. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
