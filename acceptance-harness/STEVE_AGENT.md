# Steve Agent real-client harness

## Purpose

This is a CLI-Anything harness for the real Forge 1.20.1 development client. It
does not replace Minecraft or call a server service directly. Commands cross the
same client boundary a player crosses:

```text
cli-anything-steve-agent
  -> authenticated localhost bridge
  -> real Minecraft Screen/widget or client action
  -> the production packet/channel
  -> the production server handler and world guards
```

The first milestone automates the missing Mac Level 3.5 layer: launch the real
client in the disposable player-acceptance directory, attach to the exact
allowlisted world, inspect screens/widgets, press real buttons, enter text in
real edit boxes, issue bounded player commands, use the held item, capture a
native Minecraft screenshot and package the evidence as `preview-bundle/v1`.

It does not claim that layout aesthetics or animation quality have received a
human judgement. `MAC_CLIENT_AUTOMATED_ACCEPTANCE` and
`MAC_HUMAN_UX_ACCEPTANCE` remain separate results.

## Source analysis

- Real backend: ForgeGradle `runClient`, launched only through
  `scripts/new-player-acceptance-client.sh`.
- Native state: the live `Minecraft` client, current `Screen`, actual widgets,
  the integrated-server connection and the disposable acceptance world.
- Player actions: `Button.onPress`, `EditBox.setValue` and
  `MultiPlayerGameMode.useItem`; these retain the production client packet and
  server-handler path.
- Preview backend: `Screenshot.grab` against Minecraft's real render target.
- Persistent harness state: a locked JSON session outside Git under
  `~/SteveAgentPlayerAcceptance/.steve-agent-harness/`.
- Production state: unchanged. The bridge is a second dev-only Forge mod source
  set and is absent from the production JAR.

## Command architecture

The one-shot Click CLI and default REPL expose these groups:

- `doctor`: validate the repository, mandatory launch script, external game
  root, exact world name and existing disposable save.
- `session`: create, inspect and close a random-token client session.
- `client`: launch, wait, status and stop the real Forge client.
- `screen`: inspect semantic widgets, press a widget, enter widget text and use
  the current Screen's native close path.
- `player`: send a bounded command, aim the real client hit result, bind the active
  salvage/material-source controller, use the real main-hand item, or send a
  sneak-use/sneak-use-on packet through the real `MultiPlayerGameMode` path.
- `world inspect`: read-only blocks, container slots and nearby entities visible
  to the client (bounded radius 0–3; larger payloads are refused).
- `terminal`: open the held engineer terminal and select a visible target row
  through the real Goal Picker screen.
- `scenario`: inspect the truthful catalog, recover stale disposable work through
  real terminal controls, print a Metal Press or Composite plan, or run an
  evidence-producing scenario. C-06–C-10 now share the C-03 player-flow runner;
  their real client completion and the multi-Bot matrix remain unverified.
- Composite status/cancel and the immutable completion report are exposed through
  bounded packet-backed screens. The dev-only `industrialagent acceptance
  composite-fixture` command prepares two exact source chests but never places an
  order; the subsequent `steveagent composite create ... bots ...` still crosses
  the real player command, reservation and execution path. The catalog marks this
  gate `REAL_CLIENT_FIXTURE_READY`, not passed.
- `screen.inspect` target rows include the server-provided recipe, capability, exact
  input summary, module count and typed status code. This is semantic evidence for
  capability-specific scenarios; it is not a claim that those scenarios have run.
- Ordinary player screens expose read-only packet-backed domain evidence through
  `screen.inspect`; the bridge never writes or synthesises those snapshots.
- The production courier and visible construction workers use one bounded stand-cell
  navigation contract. Flat moves and one-block horizontal stair transitions are
  supported in one route; destination feet/head clearance, sturdy floor, loaded
  chunks and the authorized region are rechecked on every move.
- `preview`: list recipes, capture a native screenshot bundle and return the
  latest bundle.
- `assert`: verify structured screen, world, PNG and report evidence.

Every command supports global `--json`. Mutation commands accept global
`--dry-run`; dry-run returns the exact intended backend request without sending
it. Session JSON writes hold an exclusive file lock and truncate only after the
lock is acquired.

## Security boundary

The bridge starts only when all of these are true:

1. Gradle receives the explicit `clientAcceptanceHarness` property from the
   mandatory launch script.
2. The game directory is outside every Git ancestor and equals the configured
   acceptance root.
3. The allowed world is exactly `Steve Agent Mac Acceptance`.
4. A fresh 256-bit session token is written to a mode-0600 one-time file inside
   the acceptance root. The bridge reads and deletes it at startup; the token
   never appears in the endpoint or JVM command line.
5. The socket binds only to the loopback interface.

The endpoint file never contains the token. Mutating bridge commands are
refused until the exact allowlisted integrated world is loaded. The token,
socket and endpoint are discarded when the client stops. The production JAR is
scanned to prove it contains neither bridge classes nor bridge mod metadata.

## Truthful evidence

Success is not inferred from process exit alone. Real-client E2E requires all
of the following:

- bridge endpoint created by the real dev client;
- exact world reported by the integrated client;
- production terminal screen observed after real held-item use;
- semantic widget inventory read from the current Screen;
- Goal Picker search results received through the production packet path;
- Minecraft-native PNG with valid signature and dimensions;
- `preview-bundle/v1` manifest whose artifact exists and matches the PNG;
- clean client shutdown and no crash report created;
- production JAR exclusion scan.

The implemented gate passes 32/32 unit tests and 10/10 installed-command/real-
client tests. It also handles stale disposable-world work through the real
manage/cancel/close UI path before opening a new line. Evidence is recorded in
`cli_anything/steve_agent/tests/TEST.md`.

## Deliberate first-milestone limits

- World creation is not automated yet. The existing exact disposable world is
  opened through Minecraft's native `--quickPlaySingleplayer` option. The
  harness refuses missing or differently named saves.
- It does not expose arbitrary filesystem, Java, packet or server-method calls.
- `screen.press` works only after the exact acceptance world is loaded.
- The Metal Press scenario runner is now built on this transport and has a
  captured completion/report PASS on the exact disposable world, including a
  down-then-up stair route. Composite/01 BOTS also has recorded completion.
  C-06–C-10 and the broader multi-Bot matrix remain client follow-ups; their
  server/runner tests do not prove player-visible completion.
