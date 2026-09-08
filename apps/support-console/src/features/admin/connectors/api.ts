import { authedFetch } from "@/lib/httpClient";
import { TOOL_INTEGRATION_GATEWAY_BASE_URL } from "@/lib/env";

const BASE = `${TOOL_INTEGRATION_GATEWAY_BASE_URL}/connectors`;

export interface Connector {
  connectorId: string;
  name: string;
  version: string;
  capabilities: string[];
  riskLevel: string;
  requiresApproval: boolean;
  healthStatus: string;
  sideEffectKind: string;
  allowedHosts: string[];
}

export interface RegisterConnectorInput {
  name: string;
  version: string;
  capabilityNames: string[];
  riskLevel: string;
  requiresApproval: boolean;
  isMutating: boolean;
  allowedHosts: string[];
}

function toConnector(row: Record<string, unknown>): Connector {
  return {
    connectorId: String(row.connector_id),
    name: String(row.name),
    version: String(row.version),
    capabilities: (row.capabilities as string[]) ?? [],
    riskLevel: String(row.risk_level),
    requiresApproval: Boolean(row.requires_approval),
    healthStatus: String(row.health_status),
    sideEffectKind: String(row.side_effect_kind),
    allowedHosts: (row.allowed_hosts as string[]) ?? [],
  };
}

export async function listConnectors(): Promise<Connector[]> {
  const response = await authedFetch(BASE, { method: "GET" });
  return ((await response.json()) as Record<string, unknown>[]).map(toConnector);
}

export async function registerConnector(input: RegisterConnectorInput): Promise<Connector> {
  const response = await authedFetch(BASE, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: input.name,
      version: input.version,
      capability_names: input.capabilityNames,
      input_schema_ref: `console://${input.name}/input`,
      output_schema_ref: `console://${input.name}/output`,
      risk_level: input.riskLevel,
      requires_approval: input.requiresApproval,
      is_mutating: input.isMutating,
      allowed_hosts: input.allowedHosts,
    }),
  });
  return toConnector((await response.json()) as Record<string, unknown>);
}

export async function updateConnectorStatus(
  connectorId: string,
  action: "enable" | "disable" | "deprecate",
  requestedBy: string,
): Promise<Connector> {
  const response = await authedFetch(`${BASE}/${connectorId}/status`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ action, requested_by: requestedBy, correlation_id: "" }),
  });
  return toConnector((await response.json()) as Record<string, unknown>);
}
