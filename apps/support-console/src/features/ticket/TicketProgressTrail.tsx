import type { ProgressStep, ProgressStepKey } from "@/features/ticket/ticketProgress";

const STATE_CLASS: Record<ProgressStep["state"], string> = {
  done: "text-ok",
  current: "text-brand-600 font-semibold",
  todo: "text-faint",
};

const DOT_CLASS: Record<ProgressStep["state"], string> = {
  done: "bg-ok",
  current: "bg-brand-600 shadow-[0_0_0_3px_var(--color-brand-100)]",
  todo: "bg-border",
};

/**
 * Concept C's horizontal trail: every step is always clickable — the real
 * `state` (done/current/todo) is a signal, never a hard gate, since the
 * backend is the actual source of truth for what a given status transition
 * allows (an operator overriding an already-done triage, say, still just
 * gets that endpoint's own real 409 if it disagrees — nothing here
 * pre-emptively hides the panel).
 */
export function TicketProgressTrail({
  steps,
  activeStep,
  onSelectStep,
}: {
  steps: ProgressStep[];
  activeStep: ProgressStepKey;
  onSelectStep: (key: ProgressStepKey) => void;
}) {
  return (
    <div className="mb-7 flex items-center" role="tablist" aria-label="Ticket progress" data-testid="ticket-progress-trail">
      {steps.map((step, index) => (
        <div key={step.key} className="flex flex-1 items-center last:flex-none">
          <button
            type="button"
            role="tab"
            aria-selected={activeStep === step.key}
            onClick={() => onSelectStep(step.key)}
            className={`flex items-center gap-2 rounded-md px-1 py-1 text-sm ${STATE_CLASS[step.state]} ${
              activeStep === step.key ? "underline decoration-2 underline-offset-4" : ""
            }`}
            data-testid={`progress-step-${step.key}`}
            data-state={step.state}
          >
            <span className={`size-[9px] shrink-0 rounded-full ${DOT_CLASS[step.state]}`} aria-hidden="true" />
            {step.label}
          </button>
          {index < steps.length - 1 ? <span className="mx-2.5 h-px min-w-[18px] flex-1 bg-border" aria-hidden="true" /> : null}
        </div>
      ))}
    </div>
  );
}
