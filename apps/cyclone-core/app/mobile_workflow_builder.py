"""AI workflow proposal pipeline: model output -> validation -> reviewable Agent-2 document."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from .workflow_guard import WorkflowValidation, WorkflowValidator, compile_workflow_candidate


class WorkflowProposalModel(Protocol):
    async def propose_workflow(
        self, *, goal: str, context: dict[str, Any]
    ) -> dict[str, Any]: ...


@dataclass(frozen=True)
class WorkflowProposal:
    goal: str
    document: dict[str, Any]
    explanation: str
    validation: WorkflowValidation
    enable_allowed: bool = False


class AIWorkflowBuilder:
    """Generate but never directly enable or execute LLM-produced workflow text."""

    def __init__(
        self,
        model: WorkflowProposalModel,
        *,
        validator: WorkflowValidator | None = None,
    ) -> None:
        self._model = model
        self._validator = validator or WorkflowValidator()

    async def propose(
        self, *, goal: str, context: dict[str, Any] | None = None
    ) -> WorkflowProposal:
        if not goal.strip():
            raise ValueError("Workflow goal must not be blank.")
        raw = await self._model.propose_workflow(
            goal=goal.strip(), context=dict(context or {})
        )
        if not isinstance(raw, dict):
            raise ValueError("Workflow model must return a structured object, not executable text.")
        validation = self._validator.validate(raw)
        if not validation.valid:
            raise ValueError("Invalid generated workflow: " + "; ".join(validation.errors))
        compiled = compile_workflow_candidate(raw, validator=self._validator)

        trigger = compiled["trigger"]
        step_count = len(compiled["steps"])
        permissions = list(validation.required_permissions)
        consequential = list(validation.consequential_steps)
        explanation = (
            f"Trigger: {trigger}. Actions: {step_count} typed steps. "
            f"Required permissions: {permissions or ['none declared']}. "
            f"Consequential steps: {consequential or ['none declared']}. "
            "The proposal is disabled until human review and Agent-2 schema compilation."
        )
        return WorkflowProposal(
            goal=goal.strip(),
            document=compiled,
            explanation=explanation,
            validation=validation,
            enable_allowed=False,
        )
