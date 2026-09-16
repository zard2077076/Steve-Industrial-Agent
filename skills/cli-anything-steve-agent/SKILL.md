---
name: cli-anything-steve-agent
description: Operate and verify Steve Industrial Agent's real disposable Forge client. Use Computer Use for player-visible interaction and the CLI bridge for supplemental structured state, commands and evidence.
---

# Steve Agent client workflow

Use only the designated external `~/SteveAgentPlayerAcceptance` root and exact
`Steve Agent Mac Acceptance` world. Do not target formal/PCL2 instances.

## Start or attach

Before a new session, run `steve-agent-test --json doctor`. Reuse a verified
existing session when available; do not reset or restart it just to begin a check.
Launch via `steve-agent-test client launch` or
`scripts/new-player-acceptance-client.sh`, never direct `runClient`.
The launcher satisfies the real Git-ancestor safety boundary without disabling it.

Prefer Computer Use for real player UI interaction. Read its current API docs.
A missing Java app-list entry is not proof the game is absent: check the game
process and supported application/window identity, then try screenshot observation
when AX is empty. Do not invent PID APIs or silently restart a user-visible game.
Use the Harness to supplement actual screen, packet, world and log evidence.

## Existing CLI

Global flags precede commands: `steve-agent-test --json --dry-run screen press new_line`.
Use installed `--help` and `scenario catalog` for the current command inventory
instead of maintaining a second frozen list of supported products.

- `client status|wait|stop`, `session create|status|close`: actual client/authority lifecycle.
- `screen inspect|press|text|close`, `terminal open|select-product`: real widgets/actions.
- `player`: bounded commands, teleport, aim and production material/salvage binding.
- `world inspect`, `scenario inspect-bots`: bounded read-only observations.
- `scenario plan|run|recover`: planned actions, actual workflow or safe stale-project recovery.
- `preview capture`, `assert`: native screenshots and artifact/state verification.

Polling should observe state, not repeatedly press Refresh. Preserve failed scenes
and errors. A fixture seeds disposable materials but never proves an order ran.

## Verification

Bind evidence to the current project, target, quantity and version. Check actual
material conservation, output, required energy and cleanup according to the scenario.
Planned/synthetic/unit results are not real-client PASS; automated visual observations
are not the user's subjective UX approval. Missing environments stay unverified while
independent work continues.

Diagnose typed errors and fix code/test/isolated configuration when authorized.
Do not preserve stale magic counts or source-string assertions for their own sake:
replace them with behavioral checks without weakening ownership or item accounting.
Development and acceptance details live in `docs/PROJECT_STATE.md` and
`docs/HANDOFF_CODEX_ACCEPTANCE.md`, not duplicated here.
