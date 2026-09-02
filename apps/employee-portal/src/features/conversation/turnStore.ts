import { create } from "zustand";
import { IllegalTurnTransitionError, transition, type TurnEvent, type TurnState } from "@/features/conversation/turnState";

interface TurnStoreState {
  state: TurnState;
  dispatch: (event: TurnEvent) => void;
  /** SPEC-EP-015: seeds the machine directly from a resumed conversation's real backend-reported state, bypassing the transition table (this is initialization, not a user-driven edge). */
  seed: (state: TurnState) => void;
}

/**
 * SPEC-EP-006: the store itself stays a thin Zustand wrapper around the pure
 * `transition` function (turnState.ts) — `dispatch` catches
 * `IllegalTurnTransitionError` and no-ops with a console warning rather than
 * throwing into React's render/event-handler call stack (a component
 * accidentally double-dispatching must not crash the whole app); the pure
 * function itself still throws, so unit tests can assert rejection
 * precisely without needing a mounted store.
 */
export const useTurnStore = create<TurnStoreState>((set, get) => ({
  state: "IDLE",
  dispatch: (event) => {
    try {
      const next = transition(get().state, event);
      set({ state: next });
    } catch (error) {
      if (error instanceof IllegalTurnTransitionError) {
        console.warn(error.message);
        return;
      }
      throw error;
    }
  },
  seed: (state) => set({ state }),
}));
