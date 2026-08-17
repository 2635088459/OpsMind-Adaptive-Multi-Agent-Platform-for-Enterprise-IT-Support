from __future__ import annotations

import hashlib
from datetime import UTC, datetime

import pytest

from memoryknowledge.application.commands import PublishMemoryCommand
from memoryknowledge.application.exceptions import (
    MemoryCandidateConflictingException,
    MemoryCandidateNotFoundException,
    MemoryNotFoundException,
)
from memoryknowledge.application.services.publish_memory import PublishMemoryService
from memoryknowledge.application.telemetry import MemoryTelemetry
from memoryknowledge.domain.enums import GraphNodeType, MemoryType
from memoryknowledge.domain.exceptions import InvalidMemoryCandidateTransitionException
from memoryknowledge.domain.ids import IdempotencyKey, MemoryCandidateId, MemoryId
from memoryknowledge.domain.memory_candidate import MemoryCandidate
from memoryknowledge.domain.values import RedactionReport, SourceRef
from memoryknowledge.infrastructure.authorization import StaticAuthorizationPolicyAdapter
from memoryknowledge.infrastructure.clock import SystemClockAdapter
from memoryknowledge.infrastructure.embedding.embedding_provider import DeterministicHashEmbeddingProvider
from memoryknowledge.infrastructure.persistence.in_memory import (
    InMemoryAuditRecordRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryEmbeddingRepository,
    InMemoryGraphEdgeRepository,
    InMemoryGraphNodeRepository,
    InMemoryMemoryCandidateRepository,
    InMemoryMemoryRepository,
    InMemoryOutboxRepository,
)
from memoryknowledge.infrastructure.redaction import RegexRedactionPolicyAdapter

pytestmark = pytest.mark.unit


def _now() -> datetime:
    return datetime.now(UTC)


def _build_service():
    candidate_repository = InMemoryMemoryCandidateRepository()
    memory_repository = InMemoryMemoryRepository()
    graph_node_repository = InMemoryGraphNodeRepository()
    graph_edge_repository = InMemoryGraphEdgeRepository()
    outbox_repository = InMemoryOutboxRepository()
    service = PublishMemoryService(
        candidate_repository, memory_repository, graph_node_repository, graph_edge_repository,
        DeterministicHashEmbeddingProvider(), InMemoryEmbeddingRepository(), RegexRedactionPolicyAdapter(),
        InMemoryCommandIdempotencyRepository(), outbox_repository, InMemoryAuditRecordRepository(),
        StaticAuthorizationPolicyAdapter(), SystemClockAdapter(), MemoryTelemetry(),
    )
    return service, candidate_repository, memory_repository, outbox_repository, graph_node_repository, graph_edge_repository


def _seed_validated_candidate(candidate_repository: InMemoryMemoryCandidateRepository) -> MemoryCandidateId:
    candidate = MemoryCandidate.extract(MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), "text", "hash-1", _now())
    candidate = candidate.redact("text", RedactionReport()).validate(confidence_score=0.8, source_refs_trusted=True)
    candidate_repository.save(candidate, expected_status=None)
    return candidate.candidate_id


def _seed_conflicting_candidate(candidate_repository: InMemoryMemoryCandidateRepository) -> MemoryCandidateId:
    candidate = MemoryCandidate.extract(MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), "text", "hash-2", _now())
    candidate = candidate.redact("text", RedactionReport()).validate(confidence_score=0.8, source_refs_trusted=True)
    candidate = candidate.mark_conflicting("conflict-set-1")
    candidate_repository.save(candidate, expected_status=None)
    return candidate.candidate_id


def _seed_high_risk_redacted_candidate(candidate_repository: InMemoryMemoryCandidateRepository) -> MemoryCandidateId:
    candidate = MemoryCandidate.extract(
        MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), "api_key: abcd1234efgh5678", "hash-3", _now(),
    )
    candidate = candidate.redact(
        "api_key: ***REDACTED***", RedactionReport(secret_patterns_matched=("key_value_secret",)),
    ).validate(confidence_score=0.8, source_refs_trusted=True)
    candidate_repository.save(candidate, expected_status=None)
    return candidate.candidate_id


def _command(candidate_id: MemoryCandidateId, idempotency_key: str = "publish-1") -> PublishMemoryCommand:
    return PublishMemoryCommand(
        candidate_id=candidate_id, usefulness_score=0.7, published_by="admin-1", idempotency_key=IdempotencyKey(idempotency_key),
        content="full resolution content", summary="short summary", source_trust_score=0.9,
    )


def test_publish_creates_memory_and_active_version_in_one_step() -> None:
    service, candidate_repository, memory_repository, outbox_repository, *_ = _build_service()
    candidate_id = _seed_validated_candidate(candidate_repository)

    view = service.publish(_command(candidate_id))

    assert view.status.name == "ACTIVE"
    assert view.version == 1
    stored = memory_repository.find_active_version(view.memory_id)
    assert stored is not None and stored.memory_version_id == view.memory_version_id
    assert candidate_repository.find_by_id(candidate_id).status.name == "PUBLISHED"
    assert any(r.event_type == "memory.published.v1" for r in outbox_repository.recorded())


def test_publish_redacts_content_and_summary_before_storing_and_embedding() -> None:
    """02-business-invariants: "Agent 看到的是 redacted content，不是 raw source";
    05-api-contracts §"API 原则": "对 Runtime 返回 redacted snippet，不返回 raw document" —
    command.content/command.summary are fresh text from this exact approve call, not
    the candidate's own already-redacted extraction text, so they must be redacted
    here too.
    """
    service, candidate_repository, memory_repository, *_ = _build_service()
    candidate_id = _seed_validated_candidate(candidate_repository)
    command = PublishMemoryCommand(
        candidate_id=candidate_id, usefulness_score=0.7, published_by="admin-1", idempotency_key=IdempotencyKey("publish-redact-1"),
        content="contact user@example.com, api_key: abcd1234efgh5678", summary="reporter email is user@example.com",
        source_trust_score=0.9,
    )

    view = service.publish(command)

    stored = memory_repository.find_version_by_id(view.memory_version_id)
    assert "user@example.com" not in stored.content
    assert "abcd1234efgh5678" not in stored.content
    assert "user@example.com" not in stored.summary
    assert stored.redaction_report.had_redactions


def test_publish_requires_an_approvable_candidate_status() -> None:
    service, candidate_repository, *_ = _build_service()
    candidate = MemoryCandidate.extract(MemoryCandidateId.new_id(), MemoryType.EPISODIC, (SourceRef("ticket", "T-1"),), "text", "hash-1", _now())
    candidate_repository.save(candidate, expected_status=None)  # still EXTRACTED

    with pytest.raises(InvalidMemoryCandidateTransitionException):
        service.publish(_command(candidate.candidate_id))


def test_publish_is_idempotent_under_the_same_key() -> None:
    service, candidate_repository, _, outbox_repository, *_ = _build_service()
    candidate_id = _seed_validated_candidate(candidate_repository)

    first = service.publish(_command(candidate_id, "publish-dup"))
    second = service.publish(_command(candidate_id, "publish-dup"))

    assert first.memory_version_id == second.memory_version_id
    assert len([r for r in outbox_repository.recorded() if r.event_type == "memory.published.v1"]) == 1


def test_publish_unknown_candidate_raises_not_found() -> None:
    service, *_ = _build_service()

    with pytest.raises(MemoryCandidateNotFoundException):
        service.publish(_command(MemoryCandidateId.new_id()))


def test_publishing_a_conflicting_candidate_with_a_blank_actor_is_rejected() -> None:
    """02-business-invariants §"记忆写入不变量": "CONFLICTING candidate 必须人工或 policy
    处理，不能自动覆盖 active memory" — the AuthorizationPort gate this service now
    consults for CONFLICTING candidates rejects a blank actor, activating the
    MEMORY_CANDIDATE_CONFLICTING error code 05-api-contracts already names.
    """
    service, candidate_repository, *_ = _build_service()
    candidate_id = _seed_conflicting_candidate(candidate_repository)
    command = PublishMemoryCommand(
        candidate_id=candidate_id, usefulness_score=0.7, published_by="   ", idempotency_key=IdempotencyKey("publish-conflict-1"),
        content="full resolution content", summary="short summary", source_trust_score=0.9,
    )

    with pytest.raises(MemoryCandidateConflictingException):
        service.publish(command)


def test_publishing_a_conflicting_candidate_with_a_real_actor_succeeds() -> None:
    """03-state-machine still allows CONFLICTING -> APPROVED for an authorized actor —
    the gate does not block the transition outright, only an unauthorized attempt.
    """
    service, candidate_repository, memory_repository, *_ = _build_service()
    candidate_id = _seed_conflicting_candidate(candidate_repository)

    view = service.publish(_command(candidate_id))

    assert view.status.name == "ACTIVE"
    assert candidate_repository.find_by_id(candidate_id).status.name == "PUBLISHED"


def test_publishing_a_high_risk_redacted_candidate_with_a_blank_actor_is_rejected() -> None:
    """11-security §"Redaction Pipeline" step 4 "Human review for high-risk
    candidate" — a candidate whose own raw evidence carried a secret must go through
    the same AuthorizationPort gate a CONFLICTING candidate does, not just a plain
    VALIDATED one, even though its status is VALIDATED (not CONFLICTING).
    """
    service, candidate_repository, *_ = _build_service()
    candidate_id = _seed_high_risk_redacted_candidate(candidate_repository)
    assert candidate_repository.find_by_id(candidate_id).status.name == "VALIDATED"
    assert candidate_repository.find_by_id(candidate_id).review_required is True
    command = PublishMemoryCommand(
        candidate_id=candidate_id, usefulness_score=0.7, published_by="   ", idempotency_key=IdempotencyKey("publish-high-risk-1"),
        content="full resolution content", summary="short summary", source_trust_score=0.9,
    )

    with pytest.raises(MemoryCandidateConflictingException):
        service.publish(command)


def test_publishing_a_high_risk_redacted_candidate_with_a_real_actor_succeeds() -> None:
    service, candidate_repository, *_ = _build_service()
    candidate_id = _seed_high_risk_redacted_candidate(candidate_repository)

    view = service.publish(_command(candidate_id))

    assert view.status.name == "ACTIVE"
    assert candidate_repository.find_by_id(candidate_id).status.name == "PUBLISHED"


def test_publishing_against_an_existing_memory_supersedes_its_active_version() -> None:
    """UC-05 step 1 "创建 Memory 或定位 existing Memory"; 08-transaction-and-outbox
    §"Publish Memory Transaction" steps 3-4: "将旧 active version 标记为 SUPERSEDED",
    "将新 version 标记为 ACTIVE". 06-event-contracts `memory.superseded.v1`.
    """
    service, candidate_repository, memory_repository, outbox_repository, *_ = _build_service()
    first_candidate_id = _seed_validated_candidate(candidate_repository)
    first = service.publish(_command(first_candidate_id))
    assert first.version == 1

    second_candidate_id = _seed_validated_candidate(candidate_repository)
    second_command = PublishMemoryCommand(
        candidate_id=second_candidate_id, usefulness_score=0.8, published_by="admin-2",
        idempotency_key=IdempotencyKey("publish-supersede-1"), content="updated resolution content",
        summary="updated summary", source_trust_score=0.95, memory_id=first.memory_id,
    )

    second = service.publish(second_command)

    assert second.memory_id == first.memory_id
    assert second.version == 2
    assert second.status.name == "ACTIVE"

    original = memory_repository.find_version_by_id(first.memory_version_id)
    assert original.status.name == "SUPERSEDED"

    current_active = memory_repository.find_active_version(first.memory_id)
    assert current_active.memory_version_id == second.memory_version_id

    assert any(r.event_type == "memory.superseded.v1" for r in outbox_repository.recorded())


def test_publish_creates_memory_and_version_graph_nodes() -> None:
    """UC-05 step 6 "upsert graph nodes / edges"; 08-transaction-and-outbox
    §"Publish Memory Transaction" step 7.
    """
    service, candidate_repository, memory_repository, outbox_repository, graph_node_repository, _ = _build_service()
    candidate_id = _seed_validated_candidate(candidate_repository)

    view = service.publish(_command(candidate_id))

    memory_node = graph_node_repository.find_by_stable_key(f"memory:{view.memory_id}", GraphNodeType.MEMORY)
    assert memory_node is not None
    assert memory_node.status.name == "VISIBLE"
    version_node = graph_node_repository.find_by_stable_key(f"memory_version:{view.memory_version_id}", GraphNodeType.MEMORY_VERSION)
    assert version_node is not None

    # 06-event-contracts `knowledge.graph.updated.v1`: "graph nodes / edges 被 ingestion
    # 或 memory publish 更新后发布" — publish is a second, explicitly-named publisher.
    assert any(r.event_type == "knowledge.graph.updated.v1" for r in outbox_repository.recorded())


def test_publish_supersede_writes_a_supersedes_edge_and_hides_the_old_version_node() -> None:
    """03-state-machine §"Graph Index 状态": "MemoryVersion superseded 时，相关
    SUPERSEDES 边新增，旧 version 节点默认 HIDDEN."
    """
    service, candidate_repository, _, _, graph_node_repository, graph_edge_repository = _build_service()
    first_candidate_id = _seed_validated_candidate(candidate_repository)
    first = service.publish(_command(first_candidate_id))

    second_candidate_id = _seed_validated_candidate(candidate_repository)
    second_command = PublishMemoryCommand(
        candidate_id=second_candidate_id, usefulness_score=0.8, published_by="admin-2",
        idempotency_key=IdempotencyKey("publish-supersede-graph-1"), content="updated resolution content",
        summary="updated summary", source_trust_score=0.95, memory_id=first.memory_id,
    )
    second = service.publish(second_command)

    old_version_node = graph_node_repository.find_by_stable_key(f"memory_version:{first.memory_version_id}", GraphNodeType.MEMORY_VERSION)
    new_version_node = graph_node_repository.find_by_stable_key(f"memory_version:{second.memory_version_id}", GraphNodeType.MEMORY_VERSION)
    assert old_version_node.status.name == "HIDDEN"
    assert new_version_node.status.name == "VISIBLE"

    [edge] = [e for e in graph_edge_repository.find_adjacent(new_version_node.node_id, limit=10) if e.to_node_id == old_version_node.node_id]
    assert edge.edge_type.name == "SUPERSEDES"


def test_publishing_against_an_unknown_memory_id_raises_not_found() -> None:
    service, candidate_repository, *_ = _build_service()
    candidate_id = _seed_validated_candidate(candidate_repository)
    command = PublishMemoryCommand(
        candidate_id=candidate_id, usefulness_score=0.7, published_by="admin-1",
        idempotency_key=IdempotencyKey("publish-missing-memory-1"), content="x", summary="x", source_trust_score=0.9,
        memory_id=MemoryId.new_id(),
    )

    with pytest.raises(MemoryNotFoundException):
        service.publish(command)


class _FailingEmbeddingProvider:
    def embed(self, text: str):
        raise RuntimeError("embedding provider unavailable")


def test_embedding_failure_during_publish_propagates_instead_of_creating_a_partial_version() -> None:
    """SPEC-MK-031 14-testing-strategy §"Recovery Tests": "embedding failure retry."
    10-failure-handling §"Embedding Failure": "对 memory publish，MVP 推荐阻塞 publish" —
    already true by construction (embed() is called before MemoryVersion.save_version()
    in PublishMemoryService._do_publish()), proven here against a provider that
    actually raises rather than just reading the source.
    """
    candidate_repository = InMemoryMemoryCandidateRepository()
    memory_repository = InMemoryMemoryRepository()
    service = PublishMemoryService(
        candidate_repository, memory_repository, InMemoryGraphNodeRepository(), InMemoryGraphEdgeRepository(),
        _FailingEmbeddingProvider(), InMemoryEmbeddingRepository(), RegexRedactionPolicyAdapter(),
        InMemoryCommandIdempotencyRepository(), InMemoryOutboxRepository(), InMemoryAuditRecordRepository(),
        StaticAuthorizationPolicyAdapter(), SystemClockAdapter(), MemoryTelemetry(),
    )
    candidate_id = _seed_validated_candidate(candidate_repository)
    command = PublishMemoryCommand(
        candidate_id=candidate_id, usefulness_score=0.7, published_by="admin-1",
        idempotency_key=IdempotencyKey("publish-embedding-failure-1"), content="x", summary="x", source_trust_score=0.9,
    )

    with pytest.raises(RuntimeError):
        service.publish(command)

    # embed() is called before save_version() in _do_publish() — a failure there
    # must never leave a MemoryVersion behind, redacted content or not.
    assert memory_repository.find_by_source_hash(hashlib.sha256(b"x").hexdigest()) is None
