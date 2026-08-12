from uuid import uuid4

from app.policy import HostAction, PolicyEngine


def test_policy_allows_read_only_process_listing_without_approval() -> None:
    decision = PolicyEngine().evaluate(
        HostAction(
            capability="process.list",
            target="localhost",
            arguments={},
            conversation_id=uuid4(),
        )
    )

    assert decision.allowed is True
    assert decision.requires_approval is False
    assert "read-only" in decision.reason.lower()


def test_policy_requires_approval_for_windows_shell_execution() -> None:
    decision = PolicyEngine().evaluate(
        HostAction(
            capability="powershell.execute",
            target="Get-Process",
            arguments={},
            conversation_id=uuid4(),
        )
    )

    assert decision.allowed is False
    assert decision.requires_approval is True
    assert "approval" in decision.reason.lower()


def test_policy_denies_workspace_escape_even_before_approval() -> None:
    decision = PolicyEngine(workspace_root="C:/Users/Agent/Documents/CycloneWorkspace").evaluate(
        HostAction(
            capability="filesystem.write",
            target="C:/Windows/System32/drivers/etc/hosts",
            arguments={"content": "unsafe"},
            conversation_id=uuid4(),
        )
    )

    assert decision.allowed is False
    assert decision.requires_approval is False
    assert "allowlisted" in decision.reason.lower()
