# Reproducible build

The release JAR uses the Git commit time, deterministic ZIP entry order and
normalized entry timestamps. Dependency availability and artifact
reproducibility are deliberately separate checks: the public network is used
once, then two builds run offline from the same verified mirror image.

Run only from the final clean committed worktree. Every path below must be new:

```powershell
.\scripts\Test-OnlineDependencyResolution.ps1 -JavaHome "<JDK17>" -GradleExecutable "<Gradle 8.4 gradle.bat>" -RootDirectory ".\work\release-online-final"
.\scripts\New-ImmutableGradleDependencyMirror.ps1 -OnlineRoot ".\work\release-online-final" -MirrorRoot ".\work\release-dependency-mirror-final"
.\scripts\Test-ReproducibleRelease.ps1 -JavaHome "<JDK17>" -GradleExecutable "<Gradle 8.4 gradle.bat>" -MirrorRoot ".\work\release-dependency-mirror-final" -RootDirectory ".\work\release-offline-ab-final"
```

The online phase uses exactly one new `GRADLE_USER_HOME`, a clean clone and
`--refresh-dependencies`. It records every cached dependency coordinate,
version, file SHA-256, observed download URL/origin, the complete build log and
the online JAR hash. It gives `maven.repo.local` a new isolated directory and
refuses `local.properties`, the ignored developer Maven cache, the user's
existing `.m2`, and any pre-existing Gradle home.

The mirror phase copies that successful cache once, removes volatile daemon and
lock state, records every remaining path/size/SHA-256 plus an aggregate hash,
and marks every snapshot file read-only. The mirror stays below ignored
`work/`; it is neither committed nor packaged.

The A/B phase verifies the mirror before use, materializes the same image into
two new independent Gradle homes, creates two new clean clones, and invokes
both builds with `--offline`. No missing dependency may fall back to a network.
The mirror is rehashed afterward. Both offline JARs must equal each other byte
for byte; only a mismatch triggers the per-entry diff.

A later source-only correction may reuse that already verified mirror without
another public-network cold download only when Git proves that every tracked
dependency-resolution input is unchanged: all Gradle settings/build/property
files plus the wrapper scripts, properties and JAR. Any change to one of those
inputs makes the mirror ineligible and requires a new online validation. The
older online JAR is not compared with a newer source commit because the online
phase validates dependency availability, while current offline A/B validates
artifact reproducibility.

The authoritative result is ignored local evidence at
`work/release-evidence/reproducible-build-current.json`. It must say PASS, bind
the current HEAD, report one online cold build plus two offline clean
checkouts/two isolated caches, no A→B cache reuse or network fallback, an
unchanged mirror, and one byte-identical SHA-256. The evidence is intentionally not
committed afterward: a documentation commit would change the Git-bound JAR and
invalidate the proof it describes.

Earlier interrupted or network-failed caches are not evidence and are never
promoted into the mirror.
The Forge development dependency is resolved from the declared official Forge
Maven repository; optional mirrors are not the only source of a clean build.
