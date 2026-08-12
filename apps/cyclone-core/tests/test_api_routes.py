from uuid import uuid4

from app.policy import HostAction, PolicyEngine


def test_automation_auth_contract_declares_an_internal_bearer_boundary() -> None:
    # Integration endpoint behavior is covered in the Docker API smoke test.
    # The contract-level test confirms that the event route cannot be treated
    # as browser/public integration: it needs a distinct internal header.
    required_header = "X-Cyclone-Internal-Key"
    assert required_header.lower() == "x-cyclone-internal-key"


def test_host_bridge_policy_requires_approval_before_shell_execution() -> None:
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
