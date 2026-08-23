"""OpsMind Tool Integration Gateway (domain 05).

SPEC-TG-001 13-package-and-class-design: layered package boundaries —
``domain`` (pure rules) -> ``ports`` (Protocols) -> ``application`` (use cases,
depends on domain+ports only) -> ``adapters`` (Protocol implementations) ->
``api``/``workers`` (call application via ``tool_gateway.container``, the only
module allowed to wire concrete adapters).

02-business-invariants INV-TG-001: Tool Gateway is the only tool execution
entry point — Agent Runtime submits a ``ToolRequest`` through the API in
``tool_gateway.api``; nothing outside this service ever calls a connector
directly.
"""

from __future__ import annotations
