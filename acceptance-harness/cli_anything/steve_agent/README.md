# CLI-Anything Steve Agent

This package operates the real Steve Industrial Agent Forge development client
through a dev-only authenticated localhost bridge. It never invokes `runClient`
directly and never targets a launcher instance or player save.

## Prerequisites

- macOS with Java 17 and this repository's Gradle wrapper.
- The disposable world
  `~/SteveAgentPlayerAcceptance/saves/Steve Agent Mac Acceptance/level.dat`.
- The mandatory `scripts/new-player-acceptance-client.sh` launcher.

The runtime source-tree refusal is intentional. Do not weaken it: the script
moves the game directory outside Git instead.

## Install

```bash
cd acceptance-harness
python3 -m venv .venv
.venv/bin/pip install -e '.[test]'
```

Both names are installed:

```bash
cli-anything-steve-agent --help
steve-agent-test --help
```

## Real-client smoke workflow

```bash
steve-agent-test --json doctor
steve-agent-test --json session create
steve-agent-test --json client launch --wait
steve-agent-test --json player command \
  'item replace entity @s weapon.mainhand with steve_create_agent:engineer_terminal'
steve-agent-test --json terminal open
steve-agent-test --json screen press new_line
steve-agent-test --json screen text search concrete
steve-agent-test --json assert target-visible minecraft:blue_concrete
steve-agent-test --json preview capture
steve-agent-test --json client stop
steve-agent-test --json session close
```

`screen press` invokes the actual `Button.onPress`. Text is written to the real
`EditBox`, so its responder sends the same production search packet. Terminal
open uses `MultiPlayerGameMode.useItem`, preserving the held-item packet/server
path.

`screen.inspect` also returns each Goal Picker row's server-provided recipe,
capability, exact input summary, module count and typed status code. Agents can
choose a C-06--C-10 target by semantic fields rather than OCR or pixel text; this
does not turn a planned scenario into a completion claim.

The reviewed C-06--C-10 representatives share the C-03 player-flow runner:

```bash
steve-agent-test --json scenario plan c06-c10 --capability c06
steve-agent-test --json scenario plan c06-c10 --capability c08
steve-agent-test --json scenario plan c06-c10 --capability c10
steve-agent-test --json --dry-run scenario run c06 --prepare-fixture
# In the designated isolated client, after connecting the current session:
steve-agent-test --json scenario run c06 --prepare-fixture --timeout 600
```

Each plan carries the exact one-batch item bill, machine/media boundary, worker
count and survival-power roles. C-06 through C-10 report `VERIFIED_SURVIVAL`, a water-wheel
source and no blocker; C-08 exposes its seven-role 8/16/32 RPM gearing chain. Planning
does not reserve materials, place blocks, or claim a client completion.
`scenario run c06` through `c10` use the real Goal Picker, exact quantity, material
selection, reservation and construction controls. Preparation seeds a live-planned
source chest, not an order. Reports must match this run's project, target, quantity
and completed stage, with exact balanced counters. Runner availability is not real
client acceptance; see the current PROJECT_STATE for verified runs.

## Real player-shaped industrial actions

The bridge also exposes bounded sneak interactions and read-only world probes;
these go through the normal client packet path rather than calling a server
order method directly. The `--` separator is required before negative
coordinates so Click does not parse them as options:

```bash
steve-agent-test --json player sneak-use-on --face UP -- -600 124 -4
steve-agent-test --json player sneak-use-on --face UP -- -606 123 0
steve-agent-test --json player sneak-use-main-hand
steve-agent-test --json world inspect --radius 3 -- -606 124 0
```

The ordinary player workflow also has semantic, non-pixel gestures:

```bash
steve-agent-test --json player look-at -- -606 124 0
steve-agent-test --json player bind-salvage -- -606 124 -8
steve-agent-test --json player bind-material-source --face UP -- -606 124 -6
steve-agent-test --json screen inspect
```

`look-at` rotates the real client player and refreshes Minecraft's hit result.
The two bind commands invoke the active production selection controllers and
send the same nonce-bound packets as a human right-click; the server still
rescans the container, ownership and inventory. They fail closed when the
corresponding selection mode is not active. `screen.inspect` additionally
exposes read-only domain state for the survey, approval, clearing, material,
construction and completion screens.

The industrial scenario catalog is deliberately truthful. Metal Press has a
real-client runner and packet-backed screen assertions, but it is not reported
as passed until the server produces a balanced ledger and restored baseline:

```bash
steve-agent-test --json scenario catalog
steve-agent-test --json scenario recover
steve-agent-test --json scenario plan metalpress
steve-agent-test --json scenario run metalpress --wait 180
```

`scenario recover` is a real player-shaped preflight: it reads the current Screen,
uses the terminal's manage/cancel controls when safe, and preserves evidence when
an order is already committed or otherwise not safely cancellable. The production
courier and Bot workers share a bounded stand-cell graph that admits flat moves and
one-block horizontal up/down stair transitions. A transition is accepted only when
the destination feet/head cells are clear and its floor is sturdy; the courier never
edits terrain or jumps through an unloaded cell.

The Metal Press fixture deliberately includes a down-then-up stair section. The
2026-08-11 real-client run completed through that route with 7 native screenshots and
the final report counters `planned=16`, `withdrawn=16`, `consumed=2`, `returned=14`,
`energyConsumed=2400`, `outputCount=1`, and all duplicate/private/unaccounted counters
at zero. Loose drops and legacy Bots in the long-lived disposable world remain a
separate physical-cleanup follow-up; they are not treated as ledger evidence.

The runner polls the read-only screen state. It does not repeatedly click the
screen's refresh button; the production screen owns its normal refresh timer.
There is no synthetic completion path. C-06--C-10 and the multi-Bot matrix still
need real-client evidence. Composite/01 BOTS has a captured completion, documented
in PROJECT_STATE; that does not prove every Composite or worker-count combination.

Multi-Bot observation is available as a read-only follow-up to a reviewed order:

```bash
steve-agent-test --json scenario plan multi-bot --worker-count 3
steve-agent-test --json scenario inspect-bots --radius 3 \
  --origin-x -606 --origin-y 124 --origin-z 0
```

`inspect-bots` samples four bounded route checkpoints and reports only the
client-visible `ConstructionBotEntity` UUID, role, tags and position. It reports
missing workers as missing, flags same-cell observations and duplicate UUIDs, and
never spawns a worker or calls an order service. It is observation evidence, not a
multi-Bot completion claim; the command still requires a real client session.

## Preview

Producer commands:

```bash
cli-anything-steve-agent --json preview recipes
cli-anything-steve-agent --json preview capture --force
cli-anything-steve-agent --json preview latest
```

The screen recipe uses Minecraft's native `Screenshot.grab` output and publishes
an immutable `preview-bundle/v1` containing `manifest.json`, `summary.json` and
`artifacts/hero.png`. When CLI-Hub is installed, it is only the viewer:

```bash
cli-hub previews inspect /absolute/path/to/bundle
cli-hub previews html /absolute/path/to/bundle -o preview.html
```

The preview is truthful client evidence, not a claim that a human approved the
layout or animation quality.

## Tests

```bash
cd acceptance-harness
.venv/bin/pytest cli_anything/steve_agent/tests/test_core.py -v
CLI_ANYTHING_FORCE_INSTALLED=1 .venv/bin/pytest \
  cli_anything/steve_agent/tests/test_full_e2e.py -v -s
```

The E2E suite requires the real Forge client. It fails rather than skips when
the client backend, exact save or mandatory script is unavailable.

## Security

- dev/test source set only; not present in the production JAR;
- `127.0.0.1` only;
- a new 256-bit token per session;
- token crosses launch through a mode-0600 one-time file, then is deleted;
- session/token paths refuse symlink redirection and the endpoint contains no token;
- mutations require the exact loaded acceptance world;
- endpoint authority is removed at shutdown and session authority at session close;
- bounded command allowlist, payload size and timeout.
