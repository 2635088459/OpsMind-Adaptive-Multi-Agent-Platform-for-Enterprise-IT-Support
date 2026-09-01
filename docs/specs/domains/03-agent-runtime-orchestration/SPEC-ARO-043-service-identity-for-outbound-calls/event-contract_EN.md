# SPEC-ARO-043 — Event Contract

Goal: support `Service Identity for Outbound Calls`.

- No event published or consumed. Token acquisition is a synchronous OIDC client_credentials exchange against Keycloak, not an event.
