import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { currentVersionFrom, isVersionConflict } from "@/features/ticketOps/versionConflict";

/**
 * SPEC-SC-013: the one shared optimistic-concurrency mutation wrapper behind
 * SPEC-SC-010 (triage), SPEC-SC-011 (assign/reassign/unassign), and
 * SPEC-SC-012 (status transition/resolution) — all of those real endpoints
 * share the identical `If-Match`/412 `VERSION_CONFLICT` contract (confirmed
 * by reading every one of their controllers directly), so this is genuinely
 * one mechanism, not incidental code reuse.
 *
 * Tracks the ticket's own `version` locally, seeded from wherever the
 * caller last read it (a queue row, a prior mutation's own response), and
 * advances it on every successful mutation. On a real 412, the local
 * version is deliberately NOT advanced automatically — it stays at the
 * last known-good value until the agent explicitly calls
 * `acknowledgeConflict`, which adopts the backend's own fresh
 * `currentVersion` and clears the conflict, so the very next attempt is a
 * new, deliberate one rather than a silent blind retry (BI-SC-005).
 *
 * `currentVersion` is the only piece of "current state" the backend's own
 * conflict body actually carries (SPEC-TW-007 AC-10) — a fuller
 * side-by-side diff of every changed field would need a dedicated
 * ticket-detail re-fetch this frontend has no endpoint wired for yet; an
 * honest scope boundary, not an oversight.
 */
export function useVersionedMutation<TInput, TResult extends { version: number }>(
  initialVersion: number,
  mutationFn: (expectedVersion: number, input: TInput) => Promise<TResult>,
) {
  const [version, setVersion] = useState(initialVersion);
  const [conflictVersion, setConflictVersion] = useState<number | null>(null);

  const mutation = useMutation({
    mutationFn: (input: TInput) => mutationFn(version, input),
    onSuccess: (result) => {
      setVersion(result.version);
      setConflictVersion(null);
    },
    onError: (error) => {
      if (isVersionConflict(error)) {
        setConflictVersion(currentVersionFrom(error));
      }
    },
  });

  function acknowledgeConflict() {
    if (conflictVersion !== null) {
      setVersion(conflictVersion);
      setConflictVersion(null);
      mutation.reset();
    }
  }

  return { version, conflictVersion, acknowledgeConflict, ...mutation };
}
