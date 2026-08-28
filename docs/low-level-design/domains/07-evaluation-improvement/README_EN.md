# Evaluation Improvement LLD

This directory defines the low-level design for `07-evaluation-improvement`.

The domain owns offline evaluation, sampled online evaluation, regression comparison, release gates, failure classification, controlled improvement candidates, canary evaluation, and rollback recommendations. It does not directly mutate production agents, prompts, policies, tools, tickets, workflows, or memory.

See `README_CN.md` for the complete design narrative. English spec files can be expanded from the same structure when implementation phases begin.

