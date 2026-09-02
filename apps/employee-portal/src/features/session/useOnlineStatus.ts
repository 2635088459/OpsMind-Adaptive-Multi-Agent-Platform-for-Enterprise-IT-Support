import { useEffect, useState } from "react";
import { AGENT_RUNTIME_BASE_URL } from "@/lib/env";
import { newTraceparent } from "@/lib/trace";

const HEARTBEAT_INTERVAL_MS = 30_000;

/**
 * SPEC-EP-019: `navigator.onLine` alone is well known to be unreliable (it
 * only reflects the OS network-interface state, not real reachability of
 * this app's own backend) — combined here with a lightweight periodic
 * heartbeat against agent-runtime-service's own real, unauthenticated
 * `/health` endpoint. Either signal turning "offline" wins immediately
 * (the browser's own `offline` event fires instantly); recovery requires
 * BOTH the browser's own `online` event AND the next heartbeat to
 * actually succeed, so a flaky reconnect doesn't flash the UI back on
 * before real connectivity is restored.
 */
export function useOnlineStatus(): boolean {
  const [isOnline, setIsOnline] = useState(navigator.onLine);

  useEffect(() => {
    const handleOffline = () => setIsOnline(false);
    const handleOnline = () => {
      void heartbeat();
    };

    async function heartbeat() {
      try {
        // SPEC-EP-023: every network call in this app carries a traceparent,
        // including this unauthenticated liveness ping — no exception for
        // "it's just a heartbeat".
        const response = await fetch(`${AGENT_RUNTIME_BASE_URL}/health`, { method: "GET", headers: { traceparent: newTraceparent() } });
        setIsOnline(response.ok);
      } catch {
        setIsOnline(false);
      }
    }

    window.addEventListener("offline", handleOffline);
    window.addEventListener("online", handleOnline);
    const interval = setInterval(() => void heartbeat(), HEARTBEAT_INTERVAL_MS);

    return () => {
      window.removeEventListener("offline", handleOffline);
      window.removeEventListener("online", handleOnline);
      clearInterval(interval);
    };
  }, []);

  return isOnline;
}
