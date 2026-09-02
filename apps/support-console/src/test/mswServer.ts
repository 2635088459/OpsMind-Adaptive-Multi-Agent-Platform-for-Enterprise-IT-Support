import { setupServer } from "msw/node";

/**
 * The one shared MSW node server every contract test in this app imports —
 * per-test files register their own `http.get/post(...)` handlers via
 * `server.use(...)` and `afterEach` resets to the (empty) baseline so one
 * test's mock never leaks into the next.
 */
export const server = setupServer();
