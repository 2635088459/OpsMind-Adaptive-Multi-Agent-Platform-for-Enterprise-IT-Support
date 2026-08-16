"""13-package-and-class-design §"类设计原则": "Entity extractor 只能基于 redacted content
和 evidenceRefs 建图." A real NLP/LLM-based entity extractor is out of this spec's
scope; this adapter pattern-matches an explicit `<NodeType>: <name>` marker syntax
(e.g. "SERVICE: vpn-auth", "SYMPTOM: mfa-loop-after-reset") rather than guessing
entities out of arbitrary prose — honest about what it can and cannot recognize,
mirroring infrastructure.embedding's own placeholder posture. IngestKnowledgeDocumentCommand.extract_graph
callers are expected to author or pre-process content with these markers until a real
extractor lands (phase-05, retrieval-and-knowledge-graph).
"""

from __future__ import annotations

import re

from memoryknowledge.application.commands import GraphEntityInput, GraphRelationInput
from memoryknowledge.domain.enums import GraphEdgeType, GraphNodeType
from memoryknowledge.domain.values import SourceRef

_MARKER_PATTERN = re.compile(r"\b(SERVICE|APPLICATION|SYMPTOM|ROOT_CAUSE|ACTION|OWNER)\s*:\s*([a-zA-Z0-9][\w.\-]*)", re.IGNORECASE)
# SYMPTOM->AFFECTS->SERVICE / ROOT_CAUSE->SUPPORTED_BY->SYMPTOM / ACTION->RESOLVED_BY->
# ROOT_CAUSE is too strong a claim for a marker-based extractor to assert; instead this
# adapter only ever proposes MENTIONS edges between consecutively-marked entities,
# leaving edge-type-specific relations to a human reviewer or a real extractor.


class MarkerBasedEntityExtractorAdapter:
    def extract(self, redacted_text: str, evidence_refs: tuple[SourceRef, ...]) -> tuple[list[GraphEntityInput], list[GraphRelationInput]]:
        matches = list(_MARKER_PATTERN.finditer(redacted_text))
        entities: list[GraphEntityInput] = []
        seen: set[tuple[GraphNodeType, str]] = set()
        for match in matches:
            node_type = GraphNodeType[match.group(1).upper()]
            raw_name = match.group(2)
            normalized_name = raw_name.strip().lower()
            key = (node_type, normalized_name)
            if key in seen:
                continue
            seen.add(key)
            entities.append(GraphEntityInput(node_type=node_type, normalized_name=normalized_name, display_name=raw_name, source_refs=evidence_refs))

        relations: list[GraphRelationInput] = [
            GraphRelationInput(
                edge_type=GraphEdgeType.MENTIONS, from_entity=entities[i], to_entity=entities[i + 1],
                confidence=0.5, evidence_refs=evidence_refs,
            )
            for i in range(len(entities) - 1)
        ]
        return entities, relations
