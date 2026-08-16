# SPEC-MK-002 Domain Rules

## 领域规则

- Schema must include versioning, visibility, unique keys, and pgvector-ready embedding storage.
- Active long-term memory 必须来自受控 pipeline。
- Candidate、MemoryVersion、KnowledgeDocument、GraphEdge 都必须有 source/evidence。
- Graph 是检索解释层，不是业务状态机。
- 删除或 deprecate 后对象不能进入默认检索。
- PII/secret/raw tool output 不得进入 active memory、chunk、log 或 retrieval response。
