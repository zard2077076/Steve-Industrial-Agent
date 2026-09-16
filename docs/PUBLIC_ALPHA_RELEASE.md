# Public Alpha release preparation

Target: Steve Industrial Agent `0.1.0-alpha.1`. This remains a local pre-RC;
no tag, push, GitHub Release or upload is authorized.

## Completed automated gates

- Production Forge TOML with JVM > environment > Forge config > safe-default precedence.
- Canonical instance/world/backup/important-root validation and reparse refusal.
- Versioned world-local test marker; marker alone grants no pilot authority.
- Portable pending request, stopped-game SHA-256 backup, immutable record,
  source pre/post equality, restore drill and restarted-JAR rehash.
- Actual packaged JAR two-process acceptance outside the repository with no
  source, `work`, legacy IWP property or important-root runtime dependency.
- Existing gravel x3, iron sheet x2, cancel, cleanup and reload/recovery regressions.

## Open gates

1. Two completely fresh, mutually isolated Gradle caches must build the final
   committed source offline to byte-identical release JARs from the already
   verified immutable mirror. The successful online cold build is not repeated
   while all tracked dependency inputs remain unchanged.
2. The replacement JAR must be visually accepted by the user in the documented
   PCL2 test workflow for
   setup/backup, gravel x3, cleanup, iron sheet x2, cancel and reload/resume.
3. Final regressions/privacy scans and zero-process/crash readback must pass.

Until all three close, status is not RC_READY and no RC tag may be created.
