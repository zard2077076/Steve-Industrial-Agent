# Clean-room validation

## Automated PASS

- A real packaged release JAR ran from a fresh repository-external Forge root.
- Forge TOML was its production config source; the world marker persisted.
- The game shut down before the portable helper copied 18 files / 8,403,645
  bytes, proved source pre/post equality and completed a restore drill.
- A second Forge process independently rehashed the portable evidence and
  passed the existing loader-neutral backup verifier.
- Repository runtime dependency, important-root writes and crash reports were zero.

## Prepared visual gate

The final independent client is prepared only after the final committed JAR is
copied into it. Minecraft remains closed until the user starts visual acceptance.
Visual PASS cannot be inferred from automated startup, old IWP evidence, logs or
class inspection. The user must confirm setup/backup, gravel x3, cleanup, iron
sheet x2, cancel and BUILD reload/resume in that exact environment.

The first A01 PCL2 run passed marker, backup, restart verification and bounded
preview, then failed closed before mutation on an external-disjoint backup-root
policy defect and an off-center two-module gravel layout. Both defects are fixed
and covered by automated regressions. That old JAR is superseded; only the
committed replacement may complete this visual gate.

## Reproducibility gate

The online cold-cache build, dependency manifest and immutable mirror passed at
`3a85050`. Dependency resolution is unchanged by the source-only visual-gate
fixes. The final committed source still requires two new checkouts and two new,
mutually isolated Gradle user homes, both forced offline from separately
materialized copies of that same verified mirror. The mirror is reusable only
while every tracked Gradle and wrapper dependency input is Git-identical.
