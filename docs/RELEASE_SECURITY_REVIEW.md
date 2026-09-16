# Release security review

This document tracks the local Public Alpha candidate review. It does not claim
that a repository, release, JAR or third-party package has been published.

## RA-01 baseline

- IWP-01 through IWP-11 are complete at baseline `5df76a8`.
- The accepted isolated player run includes visible gravel 3/3 and iron sheet
  2/2 processing, status, cancel, journal-owned cleanup and BUILD reload/resume.
- Formal-instance launcher count and write count are both zero.
- `generic-foundation-v1` resolves to
  `340d587edfe684299aa8a21ff7159f0d5d01702e`.
- High-confidence credential patterns have zero matches in current tracked
  files and in the complete Git patch history.
- Internal engineering history contains machine-specific path evidence. It is
  not copied into the public release allowlist. Rewriting remote history is out
  of scope and was not attempted.
- The repeatable scanner emits sanitized reports under the ignored
  `work/release-audit` directory and never prints matched secret values.

Run:

```powershell
.\scripts\Test-ReleasePrivacy.ps1
```

Any real credential match is a hard stop requiring revocation/rotation and
explicit user handling before release work continues.

## Final local review

- Production source scan finds no HTTP/socket client, remote download, updater,
  telemetry or automatic diagnostic upload implementation.
- Diagnostic facts are allowlisted; path/secret-shaped values redact; the ZIP
  contains only `diagnostics.txt` and declares all excluded private categories.
- Config traversal, missing paths, canonical root separation and reparse refusal
  are covered by the release configuration suite.
- Formal identity, TEST_ONLY isolation, region, ownership, stale approval,
  replay and recovery gates remain covered by the full suite and 14 real
  goal-driven GameTests.
- C-02/C-03/C-04, sole-JAR neither-mod server, C-03 recovery and C-04 recovery
  all passed on 2026-07-22 with zero crash reports.
- RC1 remains blocked on the missing standalone Forge config/marker/backup
  bootstrap, completed fresh-cache build comparison and the final human-visible
  clean-room client lifecycle. This review does not waive any gate.
