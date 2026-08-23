"""13-package-and-class-design §"Dependency Direction": "workers call application
services and do not manipulate database directly." Every worker below depends
only on an ``application.ports_in`` Protocol plus a
``ports.storage_port.ToolRequestRepository``/``ToolExecutionRepository`` *read*
method to find its own work queue — never a write, and never an adapter
directly (enforced by tests/architecture/test_api_boundary.py, which covers
both ``tool_gateway.api`` and ``tool_gateway.workers``).
"""

from __future__ import annotations
