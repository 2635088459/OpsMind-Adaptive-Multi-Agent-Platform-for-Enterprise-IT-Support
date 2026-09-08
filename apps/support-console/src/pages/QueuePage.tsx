import { QueueTable } from "@/features/queue/QueueTable";

/** SPEC-SC-001 §9 "land on the queue view". Chrome (header/nav/identity) now lives in AppLayout. */
export function QueuePage() {
  return (
    <div>
      <h1 className="text-xl font-semibold text-ink">Queue</h1>
      <div className="mt-4">
        <QueueTable filters={{}} />
      </div>
    </div>
  );
}
