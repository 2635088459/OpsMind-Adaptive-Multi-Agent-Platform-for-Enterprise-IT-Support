import { createBrowserRouter } from "react-router";
import { AuthGate } from "@/app/AuthGate";

/**
 * A single root route for now — SPEC-EP-001's own scope is login/session,
 * not navigation. `AuthGate` itself decides login-page vs. home-page purely
 * from `AuthStatus`; later specs add real routes (ticket detail, message
 * thread, etc.) as children here rather than replacing this shape.
 */
export const router = createBrowserRouter([
  {
    path: "/",
    element: <AuthGate />,
  },
]);
