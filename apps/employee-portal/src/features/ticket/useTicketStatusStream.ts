import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { TICKET_WORKFLOW_BASE_URL } from "@/lib/env";
import { useAuthStore } from "@/store/authStore";
import { newTraceparent } from "@/lib/trace";

export type StreamStatus = "connecting" | "connected" | "reconnecting" | "failed";

const INITIAL_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;
const MAX_RECONNECT_ATTEMPTS = 6;

/**
 * SPEC-EP-014 + SPEC-EP-020 (built together, same real bar this project uses
 * elsewhere for a basic-mechanism-plus-its-own-hardening pair — see e.g.
 * SPEC-ARO-038/041 in domain 03): `GET /api/v1/tickets/{id}/events` is a
 * genuinely new contract this codebase's own backend has never built
 * (confirmed: no such route exists in ticket-workflow-service's own router
 * package) — this hook is real, tested client machinery aimed at a real
 * endpoint shape, but cannot be proven live the way SPEC-EP-013's own GET
 * was; a compatibility pass is owed once that endpoint exists.
 *
 * A real, unresolved contract gap flagged rather than silently worked
 * around (SPEC-EP-014 §14's own words): the browser's native `EventSource`
 * cannot set an `Authorization` header, so this hook passes the access
 * token as a `token` query parameter — a common real-world pattern, but
 * NOT confirmed against any real backend implementation of this endpoint,
 * since none exists yet. Whichever short-lived-token mechanism that
 * endpoint's own eventual spec settles on may require changing this.
 *
 * Reconnect (SPEC-EP-020): on `onerror`, the native `EventSource` is closed
 * outright (not left to the browser's own automatic reconnect) so this
 * hook controls the real backoff timing itself; each successful reconnect
 * re-fetches SPEC-EP-013's own real GET first (`queryClient.invalidateQueries`)
 * since SSE resumption may have missed events — never resumes streaming
 * onto a possibly-stale cached value.
 */
export function useTicketStatusStream(ticketId: string | null) {
  const accessToken = useAuthStore((state) => state.accessToken);
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<StreamStatus>("connecting");
  const attemptRef = useRef(0);

  useEffect(() => {
    if (!ticketId || !accessToken) return undefined;

    let eventSource: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let unmounted = false;

    const connect = () => {
      setStatus(attemptRef.current === 0 ? "connecting" : "reconnecting");
      const url = new URL(`${TICKET_WORKFLOW_BASE_URL}/api/v1/tickets/${ticketId}/events`);
      url.searchParams.set("token", accessToken);
      // SPEC-EP-023: EventSource can't set headers at all, same real
      // limitation as the token above — a query param is this hook's own
      // best-effort until that endpoint's real spec settles the mechanism.
      url.searchParams.set("traceparent", newTraceparent());
      eventSource = new EventSource(url.toString());

      eventSource.onopen = () => {
        if (unmounted) return;
        if (attemptRef.current > 0) {
          void queryClient.invalidateQueries({ queryKey: ["ticket", ticketId] });
        }
        attemptRef.current = 0;
        setStatus("connected");
      };

      eventSource.onmessage = () => {
        // BI-EP-004: a stream update only ever moves the panel toward the real
        // backend state — the real merge-into-view logic reads from SPEC-EP-013's
        // own cached query, invalidated here so useTicket's own next render
        // re-fetches the real GET rather than this hook fabricating a merged
        // shape from an unconfirmed event payload contract.
        void queryClient.invalidateQueries({ queryKey: ["ticket", ticketId] });
      };

      eventSource.onerror = () => {
        eventSource?.close();
        if (unmounted) return;
        if (attemptRef.current >= MAX_RECONNECT_ATTEMPTS) {
          setStatus("failed");
          return;
        }
        const delay = Math.min(INITIAL_BACKOFF_MS * 2 ** attemptRef.current, MAX_BACKOFF_MS);
        attemptRef.current += 1;
        setStatus("reconnecting");
        reconnectTimer = setTimeout(connect, delay);
      };
    };

    connect();

    return () => {
      unmounted = true;
      eventSource?.close();
      if (reconnectTimer) clearTimeout(reconnectTimer);
    };
  }, [ticketId, accessToken, queryClient]);

  return status;
}
