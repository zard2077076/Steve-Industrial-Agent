# Test plan and results: Steve Agent real-client harness

## Latest scoped result — 2026-09-07

- `PATH="$PWD/acceptance-harness/.venv/bin:$PATH" CLI_ANYTHING_FORCE_INSTALLED=1 acceptance-harness/.venv/bin/pytest acceptance-harness/cli_anything/steve_agent/tests -q -k 'not real_'`:
  88 passed, 6 deselected. This is runner/installed-command evidence, not client acceptance.
  Reused after the Java-only material fix; Python paths and environment were unchanged.
- `./scripts/test-goal-driven-execution.sh`: initial 64/65 exposed route endpoint/material
  disagreement. After shared snapshot correction, 65/65 passed with seven exact live
  fixture bills and pre-mutation refusals. Log: `work/logs/create-fixture-fixed-suite-20260907.log`.
- `./gradlew test build --offline`: 932 tests, zero failures/errors; full build passed.
  Log: `work/logs/create-route-material-build-20260907.log`.
- Computer Use reported a locked Mac; new client scenarios and human UX remain unverified.
  Historical results below do not certify the new runner.

## Test inventory plan

### 2026-09-05 shared Create player runner refinement

- Reuse the C-03 player workflow for the C-06--C-10 representatives; explicitly set
  quantity through the real EditBox and check the server-provided recipe capability.
- Scripted backend tests are runner unit tests, not Minecraft/client PASS evidence.
  Cover every representative, wrong anchor/capability, insufficient materials,
  delayed source binding, paused/regressed construction, incomplete/incorrect report,
  and failure retention (no cleanup or repeated material confirmation).
- Fixture readiness must match this run's target, quantity, nonce and both physical
  client-visible chests; an older C-03 marker cannot satisfy another target.
- Exercise the actual live planner/material factory in dedicated-server GameTests:
  exact chest totals for C-03 and C-06--C-10 plus a derived target; invalid targets,
  quantities and nonces must be refused before arena changes. No project is created.
- Installed CLI dry-runs must dispatch each new scenario without launching a client.
- For physical client/UX testing, prefer Computer Use, with the existing bridge as
  supplemental packet/material/log evidence. No active display means client results
  remain deferred; synthetic runner tests cannot substitute for them.

- `test_core.py`: 57 unit tests currently collected.
- `test_full_e2e.py`: 10 installed-command and real-backend tests planned.

## Unit test plan

### `core.policy` and real-backend launch safety

- Accept the exact world name and an external disposable game root.
- Reject a game root inside the repository or beneath any Git ancestor.
- Reject a mismatched world name, missing save, missing mandatory launch script
  and an endpoint path outside the game root.
- Require a 64-hex-character session token without ever serializing it into the
  bridge endpoint.
- Pass that token through a mode-0600 one-time file, never the process command
  line, and remove the file as soon as the dev bridge reads it.
- Build a launch environment that invokes
  `scripts/new-player-acceptance-client.sh`, never direct `runClient`.

### `core.session`

- Generate a unique 256-bit token.
- Save/load round-trip with restrictive permissions.
- Lock before truncation and survive repeated writes.
- Record append-only command history without recording the token.
- Refuse malformed or schema-mismatched state.
- Close removes only the disposable endpoint/session authority.
- Refuse a session symlink without touching its target.

### `core.protocol`

- Encode one bounded newline-delimited request.
- Reject overlong command/argument values and newline injection.
- Authenticate every request and classify bridge errors.
- Resolve semantic screen/widget responses.
- Time out loudly when the real bridge is absent.

### `core.preview`

- Validate PNG signature and IHDR dimensions.
- Publish a `preview-bundle/v1` manifest using the canonical helper.
- Verify every immutable bundle artifact path.

### `core.scenarios`

- Expose a truthful scenario catalog: Metal Press and Composite/01 are implemented
  on the real client bridge; C-06–C-10/multi-Bot client gates remain unpassed.
- Keep the Metal Press BOM exact, including the Engineer's Hammer, plate mold,
  FE parts and thermal source blocks.
- Keep the scenario plan explicitly planned until a real client completion
  report is observed.
- Guard the Metal Press runner against reintroducing a tight `refresh_order`
  click loop; state polling must remain read-only.
- Exercise the stale-work preflight as a semantic Screen state machine; it must
  cancel only when the real UI exposes a safe cancel action.
- Keep the multi-Bot plan bounded to 2--5 workers and observation-only, and
  summarize only client-visible Bot role/UUID/position rows. Empty observations,
  duplicate UUIDs and same-cell observations stay explicit rather than becoming
  completion evidence.
- Keep the C-06--C-10 capability plans bound to the reviewed one-batch target and
  item/media boundary. C-06 and C-07 must expose verified water-wheel topologies without
  claiming a client PASS; C-08--C-10 must retain typed survival-power blockers and
  never turn a `REVIEW_REQUIRED` row into a reservation or free placement.
- Keep the bounded Bot navigation contract covered by the Forge unit gate: a
  one-block horizontal up/down stair edge is valid only with a clear destination
  feet/head pair and a sturdy destination floor; pure vertical teleports and
  diagonal jumps remain invalid.

## E2E test plan

### Installed CLI subprocess and packaging (7 tests)

- Resolve the installed `cli-anything-steve-agent` executable with
  `CLI_ANYTHING_FORCE_INSTALLED=1`.
- `--help` works from a directory outside the repository.
- `--json doctor` reports the mandatory launcher, external game root and exact
  save.
- `--json session create` writes a locked token-bearing session but a token-free
  endpoint contract.
- `--dry-run client launch` shows the mandatory script and never `runClient`.
- The production JAR contains neither bridge mod metadata nor bridge classes.

### True Forge client backend (3 tests)

One shared real-client workflow performs:

1. Launch through `scripts/new-player-acceptance-client.sh` with the dev-only
   bridge source set and native quick-play into `Steve Agent Mac Acceptance`.
2. Wait for authenticated localhost status and prove the exact world.
3. Send the bounded real `/item replace` player command, use the engineer
   terminal through `MultiPlayerGameMode.useItem`, and observe
   `EngineerTerminalScreen`.
4. If the disposable world contains stale work, use the real terminal's
   manage/cancel/close controls until the server reports a new line is legal.
5. Inspect `new_line`, press its actual `Button.onPress`, observe
   `GoalPickerScreen`, enter `concrete` into the real `EditBox`, and prove
   `minecraft:blue_concrete` appears in the packet-backed result rows.
6. Capture a Minecraft-native PNG, publish a `preview-bundle/v1`, validate PNG
   bytes/dimensions and all manifest paths.
7. Stop the client through the authenticated bridge, require clean process exit
   and reject any new crash report.

## Realistic workflow

**Workflow:** Search a derived product through the real player terminal.

**Simulates:** A player launches the disposable Mac client, enters the accepted
world, holds the terminal, opens it, chooses a new line and searches for a
derived target that was previously hidden by the eleven-target client list.

**Operations chained:** launch -> authenticated status -> bounded player command
-> held-item use -> terminal Screen -> real button -> Goal Picker Screen -> real
text field -> production search packet -> result inspection -> native screenshot
-> preview bundle -> shutdown.

**Verified:** exact world, real Screen classes, semantic widget geometry/state,
`minecraft:blue_concrete`, real PNG, standard preview manifest, clean exit and
production-JAR exclusion.

## Test results

- Unit suite: **57/57 passed** on macOS with Python 3.14.5 in 0.95 seconds.
- 2026-09-04 latest-build replay: the mandatory launcher compiled both production and dev-only
  bridge sources, then GLFW refused before world load with `Failed to locate a primary monitor`.
  No endpoint was created; the exact process group, session and one-time token were removed. This
  is retained as an environmental non-pass and is not substituted for the earlier real-client
  Goal Picker/screenshots or for a visual check of the new loading/empty labels.
- Real Composite/01 BOTS scenario: **PASS** in the exact disposable world at
  `-760,124,0`; project `02a18200-4708-4071-bd75-c6fdc26a08b4`, one cogwheel,
  salvage 5, every duplicate/private/unaccounted counter zero, ledger balanced
  and baseline restored. Responsive completion-page capture:
  `~/SteveAgentPlayerAcceptance/screenshots/composite-report-responsive-fixed.png`.
- Repository JVM suite: **931/931 passed**; full offline build passed and the
  production JAR contains no Harness bridge class or metadata.
- Installed CLI plus true Forge client E2E: **10/10 passed** in 36.52 seconds
  when run with the venv bin directory on `PATH`; the run verified the exact
  world, search, native screenshot and production-JAR exclusion gates.
- Native final PNG:
  `~/SteveAgentPlayerAcceptance/screenshots/steve-agent-e2e-1786416828.png`
  (411,182 bytes).
- Verified preview bundle:
  `~/SteveAgentPlayerAcceptance/.steve-agent-harness/previews/steve-agent/screen/20260811T025348Z_1cbe624b_screen`.
- The same run proved the production JAR excludes both the bridge mod metadata
  and every `dev/stevecreate/agent/acceptance/client/` class.

These results close the transport/search/screenshot foundation only. World
creation, full construction scenarios beyond the captured Metal Press gate,
visual anomaly scoring and human UX
judgement remain explicit later work. A first Metal Press scenario runner now
exists, but its completion gate is not marked passed: the disposable world
contained stale site-preparation state and the run refused before a server-backed
order could be created. The runner no longer clicks the visible refresh button
in a tight loop; it reads state while the real screen owns its refresh timer.
