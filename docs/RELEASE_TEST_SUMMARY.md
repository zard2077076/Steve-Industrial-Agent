# Public Alpha test summary

Status: **BLOCKED_PRE_RC** on 2026-07-22.

## Passed

- latest local clean build: 23 tasks and 418 XML-reported test cases with
  artifact isolation; final clean-commit manifest readback remains pending;
- C-02 Create kinetics, C-03 milling and C-04 belt pressing;
- 14 required goal-driven GameTests including gravel x3, iron sheet x2,
  cancel/rollback, cleanup ownership, reload boundaries and formal refusal;
- C-03 and C-04 recovery: two process starts, exact rescan, one real input and
  one real output, no duplicate placement/input/output, zero crash reports;
- sole-JAR install and official Forge 47.4.10 neither-mod server load;
- release version, config, privacy/history, distribution and static network
  boundary checks;
- diagnostics allowlist, redaction, compatibility mismatch and one-entry ZIP.

## Open release gates

1. Re-run two independent offline builds for the replacement commit using two
   new Gradle homes materialized from the already verified immutable mirror,
   then compare the JARs byte for byte. Do not repeat the successful online cold
   download while dependency inputs remain unchanged.
2. Run the replacement final JAR in the A01 PCL2 test world and visually confirm version,
   compatibility, gravel x3, cleanup, iron sheet x2, cancel and reload/resume.

No RC1 tag or public release is permitted until both gates pass. The first A01
candidate run is failure evidence, not a Mod failure or visual PASS: it stopped
before mutation and directly led to the backup-policy and layout corrections.

Release-profile crash reports and residual Java processes are zero. One ignored
historical crash report from a 2026-07-13 development probe is deliberately
retained as failure evidence; it was not produced by this release run.
