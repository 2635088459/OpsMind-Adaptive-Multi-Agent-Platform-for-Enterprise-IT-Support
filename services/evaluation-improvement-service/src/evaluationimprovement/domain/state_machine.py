"""13-package-and-class-design lists `domain/state_machine.py` as its own file,
distinct from every per-aggregate module — a small reusable transition-validation
helper each aggregate's own allowed-transition table is checked against, instead of
every aggregate hand-rolling the same "is this edge allowed" boilerplate.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Generic, Mapping, TypeVar

TState = TypeVar("TState")


class InvalidStateTransitionException(RuntimeError):
    """Raised whenever a StateMachine.assert_transition() call finds no edge from the
    current state to the requested target state.
    """

    def __init__(self, aggregate: str, current: object, target: object) -> None:
        super().__init__(f"{aggregate} cannot transition from {current} to {target}")
        self.aggregate = aggregate
        self.current = current
        self.target = target


@dataclass(frozen=True)
class StateMachine(Generic[TState]):
    """`allowed_transitions` maps each state to the frozenset of states directly
    reachable from it. A state absent from the mapping (or mapped to an empty
    frozenset) is terminal.
    """

    aggregate_name: str
    allowed_transitions: Mapping[TState, frozenset[TState]]

    def can_transition(self, current: TState, target: TState) -> bool:
        return target in self.allowed_transitions.get(current, frozenset())

    def assert_transition(self, current: TState, target: TState) -> None:
        if not self.can_transition(current, target):
            raise InvalidStateTransitionException(self.aggregate_name, current, target)

    def is_terminal(self, state: TState) -> bool:
        return not self.allowed_transitions.get(state)
