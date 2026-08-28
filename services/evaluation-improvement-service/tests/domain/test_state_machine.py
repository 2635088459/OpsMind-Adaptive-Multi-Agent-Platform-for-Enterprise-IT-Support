from __future__ import annotations

import pytest

from evaluationimprovement.domain.state_machine import InvalidStateTransitionException, StateMachine


@pytest.mark.unit
def test_allowed_transition_succeeds() -> None:
    sm = StateMachine("Widget", {"A": frozenset({"B"}), "B": frozenset()})
    sm.assert_transition("A", "B")  # does not raise


@pytest.mark.unit
def test_disallowed_transition_raises() -> None:
    sm = StateMachine("Widget", {"A": frozenset({"B"}), "B": frozenset()})
    with pytest.raises(InvalidStateTransitionException):
        sm.assert_transition("B", "A")


@pytest.mark.unit
def test_is_terminal() -> None:
    sm = StateMachine("Widget", {"A": frozenset({"B"}), "B": frozenset()})
    assert sm.is_terminal("B") is True
    assert sm.is_terminal("A") is False
