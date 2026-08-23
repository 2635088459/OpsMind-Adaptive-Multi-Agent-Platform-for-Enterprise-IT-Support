"""Application layer: use-case orchestration. Depends only on
``tool_gateway.domain`` and ``tool_gateway.ports`` (13-package-and-class-design
§"Dependency Direction": "application depends on domain and ports") — never on
``tool_gateway.adapters``, enforced by the import-linter "forbidden" contract in
pyproject.toml.
"""

from __future__ import annotations
