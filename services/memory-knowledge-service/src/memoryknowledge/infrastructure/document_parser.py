"""13-package-and-class-design lists `infrastructure/document_parser.py`. Splits raw
markdown/plain-text content into DocumentChunk rows on blank lines and markdown
headings — a real, deterministic chunker (not a placeholder), sized for MVP text
documents (runbooks/FAQs/SOPs per 01-domain-model's own KnowledgeDocument examples).
A layout-aware parser for other formats (PDF/HTML) is out of this spec's scope.
"""

from __future__ import annotations

import hashlib
import re

from memoryknowledge.domain.ids import DocumentChunkId, KnowledgeDocumentId
from memoryknowledge.domain.knowledge_document import DocumentChunk

_HEADING_PATTERN = re.compile(r"^(#{1,6})\s+(.*)$")


class SimpleDocumentParserAdapter:
    def parse_and_chunk(self, raw_content: str, document_id: KnowledgeDocumentId, document_version: int) -> list[DocumentChunk]:
        chunks: list[DocumentChunk] = []
        heading_path = ""
        paragraph_lines: list[str] = []
        chunk_index = 0

        def flush() -> None:
            nonlocal chunk_index
            content = "\n".join(paragraph_lines).strip()
            if not content:
                return
            chunk_hash = hashlib.sha256(content.encode()).hexdigest()
            chunks.append(DocumentChunk(
                chunk_id=DocumentChunkId.new_id(), document_id=document_id, document_version=document_version,
                chunk_index=chunk_index, content=content, token_count=len(content.split()),
                heading_path=heading_path, content_hash=chunk_hash,
            ))
            chunk_index += 1
            paragraph_lines.clear()

        for line in raw_content.splitlines():
            heading_match = _HEADING_PATTERN.match(line.strip())
            if heading_match:
                flush()
                heading_path = heading_match.group(2).strip()
                continue
            if not line.strip():
                flush()
                continue
            paragraph_lines.append(line)
        flush()

        if not chunks and raw_content.strip():
            chunk_hash = hashlib.sha256(raw_content.encode()).hexdigest()
            chunks.append(DocumentChunk(
                chunk_id=DocumentChunkId.new_id(), document_id=document_id, document_version=document_version,
                chunk_index=0, content=raw_content.strip(), token_count=len(raw_content.split()),
                heading_path="", content_hash=chunk_hash,
            ))

        return chunks
