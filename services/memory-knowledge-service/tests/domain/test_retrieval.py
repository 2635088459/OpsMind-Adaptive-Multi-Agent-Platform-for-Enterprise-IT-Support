from __future__ import annotations

import pytest

from memoryknowledge.domain.retrieval import score_text_relevance

pytestmark = pytest.mark.unit


def test_no_overlap_scores_zero() -> None:
    score = score_text_relevance("vpn login fails", "completely unrelated content")
    assert score.combined == 0.0


def test_overlap_scores_between_zero_and_one() -> None:
    score = score_text_relevance("vpn login fails after mfa reset", "vpn login works after mfa reset succeeds")
    assert 0.0 < score.combined <= 1.0


def test_exact_match_scores_highest() -> None:
    exact = score_text_relevance("vpn login fails", "vpn login fails")
    partial = score_text_relevance("vpn login fails", "vpn is slow today")
    assert exact.combined > partial.combined


def test_combined_never_exceeds_one_even_with_high_trust_and_recency() -> None:
    score = score_text_relevance("vpn login fails", "vpn login fails", recency=1.0, trust=1.0)
    assert score.combined <= 1.0
