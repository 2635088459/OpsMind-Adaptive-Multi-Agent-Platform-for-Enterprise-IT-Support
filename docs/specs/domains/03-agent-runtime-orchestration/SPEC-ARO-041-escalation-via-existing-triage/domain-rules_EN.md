# SPEC-ARO-041 — Domain Rules

Goal: support `Escalation Via Existing Triage`.

- Escalation is a workflow-instance-terminal event, not a pausable/resumable one — once triaged out, this same workflow instance never re-enters automation for that ticket.
- The category/team-routing hint the agent may have inferred (e.g. "VPN," "hardware") is passed to the triage call as a suggestion, but the real routing decision (queue/team assignment) is `02-ticket-workflow`'s own, not overridden by this spec.
