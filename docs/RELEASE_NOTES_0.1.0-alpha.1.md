# Steve Industrial Agent 0.1.0-alpha.1 release notes draft

> Experimental Alpha. This draft is not a published release and is not yet an
> RC1. Use only in a new, disposable creative test world.

## What this Alpha does

- selects, previews and confirms one bounded test region;
- performs zero-mutation readiness and dry-run checks;
- builds and physically verifies exactly `minecraft:gravel` x3 with Create
  milling and `create:iron_sheet` x2 with Create pressing;
- reports status, supports conservative cancellation, cleans only journal-owned
  blocks and resumes only from verified safe checkpoints;
- refuses configured important/formal worlds and ambiguous identities.

## Verified matrix

Windows 11, Java 17, Minecraft 1.20.1, Forge 47.4.0, Create 6.0.6,
DeceasedCraft Beta 5.10.16 and the documented PCL2 workflow.

## Important limitations

This is not a general factory Agent. It does not support arbitrary recipes,
multiple production lines, existing-base integration, survival automation,
Mekanism execution, cross-mod power, nuclear/radiation construction or direct
natural-language execution. It has no updater, telemetry or automatic upload.

## Before RC1

The runtime config/marker/backup bootstrap has passed. One online cold-cache
dependency validation and an initial offline A/B also passed; the source-only
replacement now requires a current-commit offline A/B from that verified mirror
and one complete human-visible lifecycle of the exact final JAR in the PCL2
clean-room workflow. Until then, any local staging package is
`BLOCKED_PRE_RC`, not publishable.
