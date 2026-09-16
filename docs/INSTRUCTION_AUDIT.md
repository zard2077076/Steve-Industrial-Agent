# Instruction audit — 2026-09-05

## Community guidance and development loop — 2026-09-07

Found a matching [community proposal](https://www.reddit.com/r/codex/comments/1w7x57n/before_blaming_gpt6_astra_read_its_prompting_guide/)
and checked its instruction-audit recommendations against the
[official GPT-6 Astra guide](https://developers.openai.com/api/docs/guides/latest-model).
The user did not provide an exact community URL, so this is a matching source, not
proof it is the exact post they saw. Prior edits already cover autonomy, focused
skills and proportionate verification; they were not duplicated or revalidated.

Added only a short AGENTS resume rule: keep current intent, diff, evidence, failures,
next action and active processes in the existing PROJECT_STATE; link raw logs rather
than expanding instructions. This is project continuity, not a claim to change Codex's
runtime compaction. [API compaction](https://developers.openai.com/api/docs/guides/compaction)
is a separate mechanism; no global model/configuration or experimental memory setting
was changed, and no external prompt package was installed.

Reviewed the current planning/handoff, CLI entry and tests, material planner/executor,
server wrapper, and Alpha CI workflow. Adopted a player-flow delivery loop in MASTER_PLAN:
reproduce a concrete failure, fix the shared cause, verify affected behavior, then
confirm the player path where an actual client is available. Keep current architecture;
the concrete bottleneck is divergence between verified material geometry and route
construction, not lack of another framework. Shared material-contract changes warrant
the integration suite; documentation-only changes do not. This is a workflow review,
not a claim of a full correctness audit of every source file.

## Focused follow-up — 2026-09-07

Used the current system skill-creator guidance. Only observed instruction problems
were changed; unrelated skills, system skills and the two already-validated project
skill copies were left unchanged.

| File | Concrete problem and change | Lines before → after |
|---|---|---|
| AGENTS.md | Three overlapping autonomy bullets merged; define when passing checks can be reused and require distinct risk coverage, not per-function tests | 60 → 59 |
| Personal ponytail/SKILL.md | Routine coding triggered the skill; remove generic persona, intensity modes and repeated advice, narrow to simplification requests | 102 → 21 |
| Personal ponytail-audit/SKILL.md | Generic codebase audits misrouted to complexity-only review; narrow trigger and clarify that one caller is not deletion evidence | 41 → 40 |
| Personal ponytail-review/SKILL.md | “Lean already. Ship.” conflated no complexity findings with release readiness; remove it and unsupported deletion examples | 55 → 42 |
| Personal cli-anything/SKILL.md | Mandatory advance test-plan writing for small changes; use distinct uncovered risks and explicit evidence reuse criteria | 57 → 58 |
| CLI refine reference | Required all-module/per-function audit contradicted focused routing; keep affected-path procedure only | 107 → 19 |
| CLI test reference | Required full console transcripts in Markdown and repeated whole-run bookkeeping; use relevant tests and existing evidence locations | 74 → 21 |

Total: 496 → 260 lines (236 removed net). Conditional CLI guidance remains in its
existing references, loaded only for refinement/testing; no new template or router.
Correctness, authorization, formal-save isolation, actual output, evidence identity,
and final outcome checks remain. Existing authorization is not requested again.

Validation: the four changed personal skills pass official `quick_validate.py`.
References resolve locally; AGENTS diff has no whitespace errors. Unchanged skills
reuse the 2026-09-05 successful validation because their contents did not change.
No new behavioral tests of instruction wording were added. The running project
suite was not restarted for this document-only change.

Personal pre-edit backup: `work/logs/skills-before-focused-audit-20260907.tar.gz`.
These personal files are outside repository Git; AGENTS changes are in Git.

## Scope and method

Scanned all 111 tracked project Markdown files for control language and stale
claims (2,158 matching lines); deeply reviewed current startup/handoff/roadmap,
acceptance, architecture, contributor and CLI skill instructions. The raw scan is
`work/logs/instruction-audit-markdown-20260905.txt`. This is a whole-file inventory
and targeted instruction review, not a claim that every historical technical fact
was revalidated against a fresh game run.

Reviewed the eight personal skill entrypoints and CLI mode guidance. Vendor/system
plugin skills and unrelated personal documents are not rewritten. The pet-specific
skill remains scoped to pet work; it does not govern this Minecraft project.
The skill mechanism was checked against official guidance:
[custom instructions](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
and [skills](https://learn.chatgpt.com/docs/build-skills).

## Changes

| Problem | Resolution |
|---|---|
| Two conflicting next-step lists, thousands of dated entries presented as current | Short current MASTER_PLAN/PROJECT_STATE; old details remain in Git at `cbac708` |
| Re-read everything, update four ledgers, fixed task order, Issue before any contract edit | One current evidence record, task-relevant reading/testing, proceed within authorized scope |
| Acceptance says stop on errors and never fix | Diagnose/fix/retest scoped defects; keep failures and protect formal saves |
| Java absent from app list treated as absent window | Computer Use first, cross-check actual process/application/window; no invented PID API |
| Root known issues still says fixed C-04/C-03/Composite bugs are open | Correct current issue index, retain old diagnostic history in Git |
| Stale “no MaterialLedger”, “only 11 products” and fixed phase sequence | Update entry docs; mark component/date-specific historical evidence explicitly |
| Ponytail caps tests, substitutes partial work, persists across all responses | Preserve requested outcome; risk-based coverage; no persistent mode or arbitrary output cap |
| Audit/review skill refuses edits despite user asking to optimize | Read-only by default; apply fixes when already authorized |
| CLI refine asks again; test mode hides failing runs; validator mandates irrelevant layers | Scope-based guidance, record all outcomes, reuse actual harness architecture |
| Canonical and packaged Steve Agent skills disagree | Identical concise instructions; runtime help/catalog owns command inventory |

## Removed

No duplicate Markdown archive was added. Removed obsolete transition documents:
`SITE_PREP_CONTRACT_CHANGE_REQUEST.md`, `docs/WINDOWS_PRE_MAC_HANDOFF.md`,
`docs/MACOS_PHASE_IV_INTAKE.md`, `docs/WINDOWS_PRE_MAC_COMPLETION_AUDIT.md`,
`docs/CROSS_PLATFORM_AUDIT_WINDOWS_TO_MAC.md`. They are recoverable with
`git show cbac708:<path>`; no active code/packaging caller depends on them.

Removed personal `ponytail-gain` (no installed benchmark source for its claimed
savings) and `ponytail-help` (redundant card with unsupported persistent-mode/config
claims). Kept the reusable simplify/review/audit/debt workflows. Original personal
skills are in `work/logs/personal-skills-before-instruction-audit-20260905.tar.gz`.
ROADMAP.md remains because the release packager consumes it; it is now a short,
self-contained outcome summary rather than a second conflicting task list.

## Preserved boundaries

No material ownership, ledger conservation, source/return identity, real output,
formal-save isolation or credential boundary was removed. Old magic counts and
source-string tests may be replaced by actual behavioral checks. Exact recipe and
transaction amounts remain meaningful, unlike historical total test/product counts.
No absent client/Windows test is relabeled a PASS. Development authority is distinct
from runtime player authorization.

Current work resumes at the shared Create client runner recorded in PROJECT_STATE.

Validation: all eight retained/published skill entrypoints passed `quick_validate.py`
(six personal skills plus two project copies); both project copies are byte-identical.
The validator's PyYAML dependency was installed only into ignored
`work/instruction-audit-venv`, not system Python. `git diff --check` passed and the
deleted-document reference scan found only this intentional removal inventory.
