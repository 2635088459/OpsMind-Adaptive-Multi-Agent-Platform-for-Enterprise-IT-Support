import { createBrowserRouter, Navigate } from "react-router";
import { AppLayout } from "@/app/AppLayout";
import { QueuePage } from "@/pages/QueuePage";
import { TicketDetailPage } from "@/pages/TicketDetailPage";
import { ObservabilityPage } from "@/pages/ObservabilityPage";
import { AdminPage } from "@/pages/AdminPage";
import { KnowledgeAdmin } from "@/features/admin/knowledge/KnowledgeAdmin";
import { ConnectorsAdmin } from "@/features/admin/connectors/ConnectorsAdmin";
import { PolicyAdmin } from "@/features/admin/policy/PolicyAdmin";

/**
 * Real routes now (they used to be a single state-switched page). `AppLayout`
 * is the auth gate + chrome; everything below it is only ever rendered once
 * `AuthStatus` is authenticated.
 */
export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppLayout />,
    children: [
      { index: true, element: <QueuePage /> },
      { path: "tickets/:ticketId", element: <TicketDetailPage /> },
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
