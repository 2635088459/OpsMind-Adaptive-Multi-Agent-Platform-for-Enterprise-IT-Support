"""Domain layer: pure business rules for ToolRequest/ToolExecution/ToolConnector/
ToolResultEnvelope. No framework, database, broker, or connector-SDK dependency
(13-package-and-class-design §"Dependency Direction"), enforced by the
import-linter "forbidden" contract in pyproject.toml.
"""

from __future__ import annotations
