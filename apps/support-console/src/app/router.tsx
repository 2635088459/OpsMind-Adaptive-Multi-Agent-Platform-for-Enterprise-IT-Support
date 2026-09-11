import { createBrowserRouter, Navigate } from "react-router";
import { AppLayout } from "@/app/AppLayout";
import { SupportDeskPage } from "@/pages/SupportDeskPage";
import { ObservabilityPage } from "@/pages/ObservabilityPage";
import { AdminPage } from "@/pages/AdminPage";
import { KnowledgeAdmin } from "@/features/admin/knowledge/KnowledgeAdmin";
import { ConnectorsAdmin } from "@/features/admin/connectors/ConnectorsAdmin";
import { PolicyAdmin } from "@/features/admin/policy/PolicyAdmin";

/**
 * Real routes now (they used to be a single state-switched page). `AppLayout`
 * is the auth gate + chrome; everything below it is only ever rendered once
 * `AuthStatus` is authenticated.
 *
 * `/` and `/tickets/:ticketId` are two leaves of the SAME element
 * (`SupportDeskPage`, Concept C's "queue-first split view", picked
 * 2026-09-11) rather than two separate pages — react-router reuses that
 * component instance across the navigation between them, so the queue rail
 * stays mounted (no re-fetch, no flicker) while only the detail pane swaps.
 */
export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppLayout />,
    children: [
      { index: true, element: <SupportDeskPage /> },
      { path: "tickets/:ticketId", element: <SupportDeskPage /> },
      { path: "observability", element: <ObservabilityPage /> },
      {
        path: "admin",
        element: <AdminPage />,
        children: [
          { index: true, element: <Navigate to="knowledge" replace /> },
          { path: "knowledge", element: <KnowledgeAdmin /> },
          { path: "connectors", element: <ConnectorsAdmin /> },
          { path: "policy", element: <PolicyAdmin /> },
        ],
      },
    ],
  },
]);
