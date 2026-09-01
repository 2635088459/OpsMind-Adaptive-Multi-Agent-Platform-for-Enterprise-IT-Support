# SPEC-ARO-043 — API Contract

Goal: support `Service Identity for Outbound Calls`.

- This spec exposes no HTTP endpoint of its own — it is a supporting client capability consumed internally by SPEC-ARO-038/040/041.
- It defines an internal client interface (e.g. an `OutboundServiceTokenProvider`) that those specs' own outbound HTTP calls depend on.
