# SPEC-ARO-041 — Escalation Via Existing Triage

> Domain: Agent Runtime Orchestration
>
> Phase: 10 — Conversational Intake
>
> Service: `agent-runtime-service`
>
> LLD Mapping: `04-use-cases`, `05-api-contracts`
>
> Document Status: Spec Planning

## 1. Goal

When a `conversational_intake` workflow determines it cannot or should not continue automating, route the ticket to a real support queue by calling `02-ticket-workflow`'s already-existing `POST /{ticketId}/triage` endpoint — never by creating a second ticket — and conclude the workflow instance normally.

## 2. Scope

Includes:

- The internal call to the real, already-built triage endpoint, with an actor identity representing this automated agent;
- The workflow instance's own terminal-state transition upon successful escalation.

Excludes:

- Any change to `02-ticket-workflow`'s own triage endpoint, its authorization model, or its state machine;
- Any further automation attempt on a ticket after this spec's escalation — that is entirely `10-support-console`'s territory from this point on.

## 3. Core Rules

- Escalation never creates a second ticket. The ticket already exists from SPEC-ARO-038 — this spec only triages it.
- The triage call's actor is a real, distinguishable automated-agent identity (e.g. `actor_type=AUTOMATION_AGENT`), never disguised as a human support agent.
- Once escalation succeeds, this workflow instance reaches a terminal state and is never resumed to attempt further self-service on the same ticket.
