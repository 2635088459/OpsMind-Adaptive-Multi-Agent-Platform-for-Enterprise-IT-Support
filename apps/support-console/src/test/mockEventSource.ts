/**
 * SPEC-EP-014/020's own required test double: jsdom has no real SSE
 * transport, and MSW itself doesn't intercept `EventSource` (only fetch/XHR)
 * — this class stands in for the global, giving tests direct control over
 * open/message/error firing without a real network connection.
 */
export class MockEventSource {
  static instances: MockEventSource[] = [];

  url: string;
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  close() {
    this.closed = true;
  }

  triggerOpen() {
    this.onopen?.();
  }

  triggerMessage(data: string) {
    this.onmessage?.({ data } as MessageEvent);
  }

  triggerError() {
    this.onerror?.();
  }

  static reset() {
    MockEventSource.instances = [];
  }

  static latest(): MockEventSource {
    const instance = MockEventSource.instances.at(-1);
    if (!instance) throw new Error("no MockEventSource instance was created");
    return instance;
  }
}
