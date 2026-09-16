# Steve Industrial Agent working agreement

Work in this repository. The explicitly designated external disposable client root is also in
scope for acceptance. Formal/PCL2/DeceasedCraft installations remain read-only references.
Other locations are in scope only when the current user request explicitly names them (for
example, editing personal skills). Preserve unrelated changes.

## Work toward the user's outcome

- On handoff, confirm repository/branch/status and read the short current
  `docs/PROJECT_STATE.md` and `docs/MASTER_PLAN.md`. Read architecture, tests and
  historical records only as relevant; do not reload the whole documentation tree per step.
- Current user instructions govern scope and priorities. This file governs repository
  workflow; MASTER_PLAN is the outcome roadmap, PROJECT_STATE records verified state.
  Dated phase/handoff/release notes are evidence, not fresh prohibitions or required sequencing.
- Continue authorized work without per-item confirmation. Ask only for a material missing
  decision or new authority. Runtime player approval/pause does not require another chat
  message. An unavailable display or Windows environment blocks that verification, not
  independent work; record the gap.

## Non-negotiable rules

- Do not modify formal launcher instances or important saves. Dedicated tests use isolated
  repository fixtures; player-client acceptance uses the external disposable root through
  `scripts/new-player-acceptance-client.sh` (directly or via the Harness), not direct runClient.
- Never fabricate results, hide failures or substitute an in-memory flag for physical output.
  Tests and assertions may be replaced when behavior changes or the test is wrong: retain
  meaningful regression coverage and explain the new invariant. Prefer resource conservation,
  ownership, identity and exact journal checks over obsolete magic counts/source-text matching.
- Never commit credentials, accounts, tokens, local absolute paths, saves, crash dumps, or `local.properties`.
- Pure modules (`core`, `adapter-api`) must not import Minecraft, Forge, Create, or Mekanism classes.
- Mod-internal APIs are confined to versioned adapter implementation packages.
- LLM output is untrusted. It may propose candidates but cannot directly execute free text, arbitrary code, or unchecked coordinates.
- World reads and mutations are server-authoritative and bounded. Network calls, LLM requests, blocking waits, and large graph searches never run on the server thread.
- High-risk Mekanism nuclear/radiation automation stays disabled until its separate safety gate is implemented and explicitly approved per plan.

## Verification and handoff

Use existing frameworks and retain tests for distinct failure risks, not one test per
function or instruction. Reuse a recorded passing check when its inputs, relevant code,
dependencies and environment are unchanged; rerun affected checks and state the reuse
basis. A source file being unchanged alone is insufficient when its dependencies changed.
Unit, dedicated-server, automated-client and human-UX results are distinct. Prefer Computer
Use for real UI tests; the Harness can supplement state/log evidence. A missing app-list entry does not prove a
Java game is absent: check the actual process, runtime application identity and accessible
window before declaring a blocker; do not silently restart an existing game.

Update PROJECT_STATE once per coherent milestone with outcome, commands, evidence and gaps.
Change MASTER_PLAN when priorities or outcomes change; change other docs only where their
content actually changes. No mandatory four-ledger updates, per-edit commits, full surveys or
fixed PASS-line counts. Review the diff and commit coherent verified work when appropriate.

Useful commands:

```bash
./gradlew test build --offline
# Prefer the relevant scripts/test-*.sh on macOS; use PowerShell counterparts on Windows.
./scripts/new-player-acceptance-client.sh
```

## Long-running work

Keep PROJECT_STATE as the single resume point: current outcome, changed paths, latest
evidence/failures, next action, and any still-running process/session. Keep raw logs in
work/logs and link them; do not paste transcripts into instructions. After context
compression, check that state and the live diff before resuming, not the whole history.
This preserves task continuity; it does not configure or replace Codex's compaction.
