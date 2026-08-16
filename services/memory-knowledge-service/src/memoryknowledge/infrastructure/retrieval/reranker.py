"""01-domain-model §"Graph 如何使用" step 4: "根据 edge type、confidence、recency、source
trust rerank." Real seed-node graph-path generation (the caller supplying non-empty
graph_paths) is phase-05 scope; when SearchMemoryService calls this with no paths yet,
this adapter is honest about it — it returns the input order unchanged rather than
inventing an explanation, matching 02-business-invariants' "不得 ... 伪造历史证据" posture.
"""

from __future__ import annotations

from memoryknowledge.domain.values import GraphPath, RetrievalResultItem


class SimpleGraphRerankerAdapter:
    def rerank(self, results: tuple[RetrievalResultItem, ...], graph_paths: tuple[GraphPath, ...]) -> tuple[RetrievalResultItem, ...]:
        if not graph_paths:
            return results

        boost_by_source_id: dict[str, float] = {}
        for path in graph_paths:
            for node_id in path.node_ids:
                boost_by_source_id[node_id] = max(boost_by_source_id.get(node_id, 0.0), path.path_score)

        def _effective_score(item: RetrievalResultItem) -> float:
            boost = boost_by_source_id.get(item.source_id, 0.0)
            return item.score + (boost * 0.2)

        return tuple(sorted(results, key=_effective_score, reverse=True))
