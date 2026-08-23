"""API (interfaces) layer: FastAPI routers. Calls only
``tool_gateway.application`` use cases (via Protocols in
``tool_gateway.application.ports_in``, handed to routers by
``tool_gateway.container``) — never ``tool_gateway.adapters`` directly. Enforced
by tests/architecture/test_api_boundary.py (an import-linter "forbidden"
contract would also flag the legitimate api -> tool_gateway.container ->
adapters composition-root edge; see that test's own docstring).
"""

from __future__ import annotations
