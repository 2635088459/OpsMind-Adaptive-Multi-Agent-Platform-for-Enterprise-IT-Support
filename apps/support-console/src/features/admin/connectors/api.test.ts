import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mswServer";
import { useAuthStore } from "@/store/authStore";
import { TOOL_INTEGRATION_GATEWAY_BASE_URL } from "@/lib/env";
import { listConnectors, registerConnector, updateConnectorStatus } from "@/features/admin/connectors/api";

const BASE = `${TOOL_INTEGRATION_GATEWAY_BASE_URL}/internal/tool-gateway/v1/connectors`;

const ROW = {
  connector_id: "c-1", name: "keycloak-identity-unlock", version: "1.0.0", capabilities: ["identity.user.unlock"],
  risk_level: "MEDIUM", requires_approval: false, health_status: "ACTIVE", side_effect_kind: "MUTATING",
  allowed_hosts: ["keycloak"], deny_by_default: true, connect_timeout_seconds: 5, invoke_timeout_seconds: 30,
  max_attempts: 3,
};

describe("connectors admin api", () => {
  beforeEach(() => {
    useAuthStore.setState({ status: "authenticated", accessToken: "fake-token", error: null });
  });

  it("lists connectors, mapping snake_case to camelCase", async () => {
    server.use(http.get(BASE, () => HttpResponse.json([ROW])));
    const [connector] = await listConnectors();
    expect(connector).toMatchObject({
      connectorId: "c-1", name: "keycloak-identity-unlock", capabilities: ["identity.user.unlock"],
      riskLevel: "MEDIUM", healthStatus: "ACTIVE", sideEffectKind: "MUTATING",
    });
  });

  it("registers a connector with the derived schema refs and snake_case body", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(BASE, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(ROW);
      }),
    );

    await registerConnector({
      name: "custom", version: "2.0.0", capabilityNames: ["a.b"], riskLevel: "HIGH",
      requiresApproval: true, isMutating: true, allowedHosts: ["h"],
    });

    expect(body).toMatchObject({
      name: "custom", version: "2.0.0", capability_names: ["a.b"], risk_level: "HIGH",
      requires_approval: true, is_mutating: true, allowed_hosts: ["h"],
      input_schema_ref: "console://custom/input", output_schema_ref: "console://custom/output",
    });
  });

  it("PATCHes connector status", async () => {
    let method: string | null = null;
    let body: Record<string, unknown> | null = null;
    server.use(
      http.patch(`${BASE}/c-1/status`, async ({ request }) => {
        method = request.method;
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...ROW, health_status: "DISABLED" });
      }),
    );

    const result = await updateConnectorStatus("c-1", "disable", "agent-1");
    expect(method).toBe("PATCH");
    expect(body).toEqual({ action: "disable", requested_by: "agent-1", correlation_id: "" });
    expect(result.healthStatus).toBe("DISABLED");
  });
});
