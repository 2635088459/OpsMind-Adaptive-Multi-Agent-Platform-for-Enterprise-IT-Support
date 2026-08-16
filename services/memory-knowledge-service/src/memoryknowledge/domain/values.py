"""Shared domain value objects. Not literally listed as their own file in
13-package-and-class-design's package tree (which groups them under each
aggregate's "值对象" prose in 01-domain-model instead), but factored out here —
mirroring domain/enums.py, domain/ids.py, domain/exceptions.py already being
pragmatic extensions beyond that literal tree — because SourceRef/EmbeddingRef/
RedactionReport are used by domain.memory, domain.memory_candidate, and
domain.knowledge_document alike, and AccessScope/GraphPath/RetrievalScore/EntityKey
by domain.retrieval and domain.knowledge_graph alike; keeping them in any single
aggregate module would create an artificial cross-aggregate-module dependency.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class SourceRef:
    """01-domain-model §"值对象": `sourceType + sourceId + version + fieldPath`.
    02-business-invariants: "Active MemoryVersion 必须至少有一个 SourceRef"; "GraphEdge
    必须有 evidenceRefs"; "Candidate、MemoryVersion、KnowledgeDocument、GraphEdge 都必须有
    source/evidence" (SPEC-MK-001 domain-rules).
    """

    source_type: str
    source_id: str
    version: int | None = None
    field_path: str | None = None

    def __post_init__(self) -> None:
        if not self.source_type or not self.source_type.strip():
            raise ValueError("sourceType must not be blank")
        if not self.source_id or not self.source_id.strip():
            raise ValueError("sourceId must not be blank")


@dataclass(frozen=True, slots=True)
class EmbeddingRef:
    """01-domain-model §"值对象": `provider + model + dimensions + vectorId`."""

    provider: str
    model: str
    dimensions: int
    vector_id: str

    def __post_init__(self) -> None:
        if self.dimensions <= 0:
            raise ValueError("dimensions must be positive")


@dataclass(frozen=True, slots=True)
class RedactionReport:
    """01-domain-model §"值对象": redacted fields, secret patterns, policy rule ids.
    02-business-invariants: "长期记忆必须保存 redaction report."
    """

    redacted_fields: tuple[str, ...] = ()
    secret_patterns_matched: tuple[str, ...] = ()
    policy_rule_ids: tuple[str, ...] = ()

    @property
    def had_redactions(self) -> bool:
        return bool(self.redacted_fields or self.secret_patterns_matched)


@dataclass(frozen=True, slots=True)
class AccessScope:
    """01-domain-model §"值对象": tenant, application, queue, role, classification.
    02-business-invariants §"检索不变量": "检索必须应用 tenant、role、classification 和
    document ACL 过滤."
    """

    tenant: str
    role: str
    classification: str
    application: str | None = None
    queue: str | None = None

    def __post_init__(self) -> None:
        if not self.tenant or not self.tenant.strip():
            raise ValueError("tenant must not be blank")
        if not self.role or not self.role.strip():
            raise ValueError("role must not be blank")
        if not self.classification or not self.classification.strip():
            raise ValueError("classification must not be blank")


@dataclass(frozen=True, slots=True)
class GraphPath:
    """01-domain-model §"值对象": `nodeIds + edgeIds + pathScore + explanation`.
    05-api-contracts: "Graph path 字段是解释和 rerank input，不是业务 action."
    """

    node_ids: tuple[str, ...]
    edge_ids: tuple[str, ...]
    path_score: float
    explanation: str


@dataclass(frozen=True, slots=True)
class EntityKey:
    """01-domain-model §"值对象": `nodeType + normalizedName + namespace` — used to derive
    a GraphNode's stableKey without duplicate entities across ingestion sources.
    """

    node_type: str
    normalized_name: str
    namespace: str

    def as_stable_key(self) -> str:
        return f"{self.namespace}:{self.node_type.lower()}:{self.normalized_name}"


@dataclass(frozen=True, slots=True)
class RetrievalScore:
    """01-domain-model §"值对象": semantic/keyword/recency/trust/success/humanValidation
    sub-scores. 02-business-invariants §"检索不变量": "Retrieval score 不能只依赖 embedding
    similarity" — .combined is a weighted blend, never semantic alone.
    """

    semantic: float
    keyword: float = 0.0
    recency: float = 0.0
    trust: float = 0.0
    success: float = 0.0
    human_validation: float = 0.0

    _WEIGHTS = {"semantic": 0.4, "keyword": 0.15, "recency": 0.1, "trust": 0.15, "success": 0.1, "human_validation": 0.1}

    @property
    def combined(self) -> float:
        total = (
            self.semantic * self._WEIGHTS["semantic"]
            + self.keyword * self._WEIGHTS["keyword"]
            + self.recency * self._WEIGHTS["recency"]
            + self.trust * self._WEIGHTS["trust"]
            + self.success * self._WEIGHTS["success"]
            + self.human_validation * self._WEIGHTS["human_validation"]
        )
        return max(0.0, min(1.0, total))


@dataclass(frozen=True, slots=True)
class Provenance:
    """05-api-contracts §"Runtime API" response shape: `sourceType + sourceRef + redacted`."""

    source_type: str
    source_ref: str
    redacted: bool


@dataclass(frozen=True, slots=True)
class RetrievalResultItem:
    """05-api-contracts §"Runtime API" `results[]` shape. `snippet` must already be
    redacted (02-business-invariants: "Agent 看到的是 redacted content，不是 raw source").
    """

    result_type: str
    source_id: str
    source_version: int
    snippet: str
    score: float
    provenance: Provenance
    graph_paths: tuple[GraphPath, ...] = field(default_factory=tuple)
