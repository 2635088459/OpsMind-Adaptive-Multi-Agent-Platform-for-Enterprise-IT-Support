"""SPEC-ARO-038 (phase-10 Conversational Intake): the conversation REST surface's own
identity handling.

This service has no real inbound JWT-verification middleware yet (no sibling Python
service in this platform does either at the HTTP boundary — see
evaluationimprovement.interfaces.security's own precedent, which reads a
caller-asserted `X-Actor-Id`/`X-Actor-Role` header pair as an honest placeholder for
real 01-user-access-authentication integration). This module takes a related but
distinct approach, forced by SPEC-ARO-038's own real requirement: the raw
`Authorization` bearer token itself must be forwarded unmodified to
02-ticket-workflow's own `POST /api/v1/tickets` (see TicketWorkflowClientPort's
docstring for why), so this endpoint cannot get away with a caller-asserted header
substitute the way a purely-internal endpoint can — it needs the real token payload.

`requester_subject()` decodes the JWT's `sub` claim WITHOUT verifying its signature.
This is intentionally not a new, weaker trust boundary: the same raw token is
forwarded downstream to 02-ticket-workflow, whose own Spring Security OAuth2
resource-server configuration performs the real signature/issuer/expiry verification
against the shared Keycloak realm before it ever records a requesterId — an invalid or
tampered token is rejected there, not here. Reading `sub` from the unverified payload
here is therefore only ever used for this service's own internal bookkeeping
(WorkflowInstanceRecord.requester_subject, SPEC-ARO-042), never as this service's own
authorization decision. A real, independently-verifying JWT dependency for this
service's own inbound authorization remains a future cross-domain-contracts spec, the
same honest gap evaluation-improvement-service's own security module already flags.
"""

from __future__ import annotations

import base64
import json

from fastapi import Depends, Header, HTTPException, status

_BEARER_PREFIX = "Bearer "


def forwarded_bearer_token(authorization: str = Header(...)) -> str:
    if not authorization.startswith(_BEARER_PREFIX):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authorization header must be a Bearer token")
    token = authorization[len(_BEARER_PREFIX):].strip()
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authorization header must be a Bearer token")
    return token


def requester_subject(token: str = Depends(forwarded_bearer_token)) -> str:
    subject = _decode_subject_unverified(token)
    if not subject:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="token carries no subject claim")
    return subject


def _decode_subject_unverified(token: str) -> str | None:
    parts = token.split(".")
    if len(parts) != 3:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="malformed bearer token")
    try:
        payload_segment = parts[1]
        padding = "=" * (-len(payload_segment) % 4)
        payload = json.loads(base64.urlsafe_b64decode(payload_segment + padding))
    except (ValueError, UnicodeDecodeError) as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="malformed bearer token") from exc
    subject = payload.get("sub")
    return str(subject) if subject else None
