# Cyclone Agent CLI Reference

All mutating task commands require the team's exact frozen `--base-sha`. Commands return stable,
sorted JSON. User-actionable failures return exit code `2` and a JSON error on stderr.

```text
cyclone-agent status [--team TEAM]

cyclone-agent team create
  --team-id ID --name NAME --captain AGENT --base-sha SHA

cyclone-agent team member-add
  --team TEAM --actor CAPTAIN --member AGENT --base-sha SHA

cyclone-agent team member-remove
  --team TEAM --actor CAPTAIN --member AGENT --base-sha SHA

cyclone-agent task list --team TEAM

cyclone-agent task add
  --team TEAM --actor CAPTAIN --task-id TASK --owner-lane LANE
  --owned-path PATH_OR_SUBTREE [--owned-path ...]
  [--forbidden-path PATH_OR_SUBTREE ...]
  [--depends-on TASK ...] [--parent TASK]
  --base-sha SHA

cyclone-agent task claim
  --team TEAM --task TASK --agent AGENT --base-sha SHA [--lease-seconds 900]

cyclone-agent task start
  --team TEAM --task TASK --attempt ATTEMPT --base-sha SHA

cyclone-agent task renew
  --team TEAM --task TASK --attempt ATTEMPT --base-sha SHA [--lease-seconds 900]

cyclone-agent task complete
  --team TEAM --task TASK --attempt ATTEMPT --base-sha SHA --bundle handoff.json

cyclone-agent task approve
  --team TEAM --task TASK --actor CAPTAIN --base-sha SHA

cyclone-agent task retry
  --team TEAM --task TASK --actor CAPTAIN --base-sha SHA

cyclone-agent task cancel
  --team TEAM --task TASK --actor CAPTAIN --base-sha SHA --reason TEXT

cyclone-agent task validate-paths --team TEAM --task TASK PATH [PATH ...]

cyclone-agent handoff validate --team TEAM --task TASK --bundle handoff.json

cyclone-agent mailbox send
  --team TEAM --sender AGENT --recipient AGENT [--task TASK] --body TEXT

cyclone-agent mailbox list --team TEAM --recipient AGENT [--after SEQUENCE]
cyclone-agent events --team TEAM [--after SEQUENCE]
```

Only the captain can create task definitions, approve review, retry failure or cancel work. Workers
claim with an attempt lease, start that exact attempt, renew it when necessary, and submit evidence
with that same attempt. An expired or superseded attempt returns `STALE_ATTEMPT`.
