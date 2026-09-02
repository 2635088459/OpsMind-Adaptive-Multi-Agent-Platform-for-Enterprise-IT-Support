import { EVALUATION_IMPROVEMENT_BASE_URL } from "@/lib/env";
import { newTraceparent } from "@/lib/trace";
import { ApiError, parseApiError } from "@/lib/apiError";
import type { RegressionReportView, RunView, ScoreView } from "@/features/evaluation/types";

/**
 * SPEC-SC-015: real, already-implemented `GET /evaluation/runs/...` reads
 * (evaluation-improvement-service). Plain `fetch`, not `authedFetch` — this
 * service's own real caller-identity mechanism is a caller-asserted
 * `X-Actor-Id`/`X-Actor-Role` header pair (confirmed by reading
 * `interfaces/security.py` directly: "a future cross-domain-contracts spec"
 * not yet built, no JWT/bearer validation at all), NOT this app's OAuth2
 * bearer token, which this service never checks. Every read this feature
 * calls is deliberately open to a caller who asserts no identity at all
 * (05-api-contracts's own default read floor, `EVALUATION_VIEWER`) — this
 * app sends no actor headers, relying purely on that documented default, so
 * it introduces no new spoofing surface beyond what already exists for any
 * caller reaching this port.
 */
async function get<T>(path: string): Promise<T> {
  const response = await fetch(`${EVALUATION_IMPROVEMENT_BASE_URL}${path}`, {
    method: "GET",
    headers: { Accept: "application/json", traceparent: newTraceparent() },
  });
  if (!response.ok) {
    throw await parseApiError(response);
  }
  return (await response.json()) as T;
}

export async function fetchRun(runId: string): Promise<RunView> {
  return get<RunView>(`/evaluation/runs/${runId}`);
}

export async function fetchScores(runId: string): Promise<ScoreView[]> {
  return get<ScoreView[]>(`/evaluation/runs/${runId}/scores`);
}

/** `null` on a real 404 — "no regression report exists yet for this run" is a genuine, expected state (never compared/gated), not a fetch failure. */
export async function fetchRegressionReport(runId: string): Promise<RegressionReportView | null> {
  try {
    return await get<RegressionReportView>(`/evaluation/runs/${runId}/regression-report`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}
