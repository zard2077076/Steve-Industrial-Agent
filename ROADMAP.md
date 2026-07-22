# Roadmap

This roadmap is directional, not a promise that a candidate feature is safe or
scheduled. Each item requires its own typed safety gate and real acceptance.

## Alpha maintenance

- harden the completed standalone runtime configuration, world marker and
  portable backup flow using more launcher/locale/path fixtures;
- compatibility fixes and clearer player-facing errors;
- additional privacy/diagnostic redaction tests;
- repeatable clean-room installation checks;
- conservative cleanup and recovery hardening.
- friendlier in-game setup screens while retaining the same fail-closed authority.

## Candidate later versions

- additional verified Create milling and pressing recipes;
- broader launcher and operating-system validation;
- guided config migration and backup retention management;
- more verified Create recipes, each with real block/entity output evidence;
- independently bounded multiple regions and production lines;
- an official-version-pinned Mekanism adapter and non-nuclear power support;
- cross-mod item and power transport after real physical evidence exists;
- constrained natural-language proposal input that still cannot directly
  execute free text, arbitrary code or unchecked coordinates.

## Not planned for the current Alpha

Formal important-save automation, nuclear/radiation construction, arbitrary
factory generation, survival resource theft, automatic updates, telemetry and
unsolicited network calls are not part of `0.1.0-alpha.1`.
