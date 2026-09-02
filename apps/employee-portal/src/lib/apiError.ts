/**
 * Every real backend this app calls (agent-runtime-service, ticket-workflow-
 * service, user-access-authentication-service) shares the same error
 * envelope shape — confirmed by reading each service's own exception-handler
 * module directly: `{error: {code, message, correlationId, details}}`. One
 * shared parser, not three per-service copies.
 */
export interface ApiErrorBody {
  code: string;
  message: string;
  correlationId?: string;
  details?: Record<string, unknown>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | undefined;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.name = "ApiError";
    this.status = status;
    this.code = body.code;
    this.correlationId = body.correlationId;
  }
}

/**
 * Reads the shared `{error: {...}}` envelope when present; falls back to a
 * generic message keyed on the HTTP status alone when the body isn't in that
 * shape (a proxy/gateway error, a genuinely unexpected non-JSON response) —
 * never throws while trying to report an error.
 */
export async function parseApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as { error?: ApiErrorBody };
    if (body.error) {
      return new ApiError(response.status, body.error);
    }
  } catch {
    // fall through to the generic case below
  }
  return new ApiError(response.status, { code: "UNKNOWN_ERROR", message: `Request failed with status ${response.status}` });
}
