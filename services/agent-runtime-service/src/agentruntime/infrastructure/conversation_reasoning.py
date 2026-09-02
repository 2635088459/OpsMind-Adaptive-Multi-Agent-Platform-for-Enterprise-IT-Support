"""SPEC-ARO-039 (phase-10 Conversational Intake): StaticConversationReasoningAdapter —
a deliberately simple, keyword-based placeholder for the real LLM/LangGraph-based
reasoning this conversation flow needs. No real LLM integration exists anywhere in
this codebase (confirmed by grepping the whole service for langgraph/langchain/
anthropic/openai — none found); the frozen technology-baseline itself lists "Agent
Orchestration: LangGraph behind internal abstractions" as "Provisional," not built.

This mirrors LoggingToolGatewayPort/NoOpTicketSnapshotPort/StaticCapabilityPolicyAdapter's
own established precedent in this exact codebase: a real, working, honestly-labeled
placeholder adapter behind a real port (ConversationReasoningPort), swappable for a
genuine LLM-backed adapter once one is built — never fabricated as if this were
already real reasoning.
"""

from __future__ import annotations

from agentruntime.application.records import KnowledgeSnippet, ReasoningOutcome

# Deliberately small, illustrative keyword sets — not a real intent classifier.
_ESCALATION_KEYWORDS = ("broken screen", "won't turn on", "hardware", "physical damage", "smoke", "burning smell")
_SELF_SERVICE_KEYWORDS = ("reset my password", "password reset", "unlock my account", "forgot my password")


class StaticConversationReasoningAdapter:
    def decide(self, message_text: str, knowledge_snippets: list[KnowledgeSnippet]) -> ReasoningOutcome:
        lowered = message_text.lower()

        if any(keyword in lowered for keyword in _ESCALATION_KEYWORDS):
            return ReasoningOutcome(
                kind="escalation",
                escalation_reason="This looks like a hardware issue that needs a human technician to inspect in person.",
            )

        if any(keyword in lowered for keyword in _SELF_SERVICE_KEYWORDS):
            return ReasoningOutcome(
                kind="proposed_action", action_summary="Send a password-reset link to your registered email.",
                action_risk_level="LOW",
            )

        if knowledge_snippets:
            top = max(knowledge_snippets, key=lambda snippet: snippet.score)
            return ReasoningOutcome(kind="text", text=f"Here's what I found that might help: {top.snippet}")

        return ReasoningOutcome(
            kind="text",
            text="Thanks for the details — could you tell me a bit more about what you're seeing?",
        )
