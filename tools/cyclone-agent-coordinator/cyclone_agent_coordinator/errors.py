"""Typed failures returned by the Cyclone development orchestrator."""

from __future__ import annotations


class CoordinatorError(Exception):
    """Base class for deterministic, user-actionable coordinator failures."""

    code = "COORDINATOR_ERROR"


class ValidationError(CoordinatorError):
    code = "VALIDATION_ERROR"


class NotFoundError(CoordinatorError):
    code = "NOT_FOUND"


class ConflictError(CoordinatorError):
    code = "CONFLICT"


class TransitionError(CoordinatorError):
    code = "INVALID_TRANSITION"


class DependencyBlockedError(CoordinatorError):
    code = "DEPENDENCY_BLOCKED"


class StaleAttemptError(CoordinatorError):
    code = "STALE_ATTEMPT"


class AuthorizationError(CoordinatorError):
    code = "COORDINATOR_AUTHORITY_REQUIRED"
