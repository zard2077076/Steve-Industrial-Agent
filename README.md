# Steve Industrial Agent

> **Experimental Alpha — only use this version in a new, disposable test world.**
> Do not install it in an important save or a formal long-term world.

Steve Industrial Agent is a Minecraft 1.20.1 Forge mod for bounded, typed and
verifiable industrial automation. Version `0.1.0-alpha.1` is the first playable
writable-world pilot. It can build and run the two explicitly supported Create
production paths while enforcing test-world opt-in, verified backup evidence,
bounded region approval, dry-run/readiness checks, cancellation, cleanup and
reload recovery.

The mod is a normal Forge mod. Put its JAR in the `mods` directory of a
compatible instance. PCL2 is the launcher used for the first Windows client
acceptance; it is not a runtime dependency. Core operation requires no Codex,
model API or internet connection.

## Supported environment

- Windows 11: verified
- PCL2: primary verified launcher for this release
- Minecraft: 1.20.1
- Forge runtime: 47.4.0
- Create: 6.0.6
- DeceasedCraft: Beta 5.10.16
- Prism Launcher and other launchers: not release-blocking and not verified yet

## Current playable capabilities

- Test-world marker and important-world protection
- Verified offline backup and restore-drill evidence
- Bounded region selection and preview
- Dry-run and twenty-five-check readiness validation
- `gravel x3` automated production
- `iron_sheet x2` automated production
- `BUILD / CONNECT / FEED / PROCESS / VERIFY` execution stages
- Status inspection, bounded cancellation and journal-owned cleanup
- BUILD recovery after save, exit and re-entry

## Not supported yet

- Bot construction groups
- Full scheduling across multiple production lines
- Most other Create machines
- Immersive Engineering
- Electrical grids or nuclear reactors
- Natural-language execution
- Autonomous operation in an important or formal main save

## Installation and commands

- [Installation](INSTALLATION.md)
- [Quick start](QUICK_START.md)
- [Complete command reference and usage](COMMAND_REFERENCE.md)
- [Compatibility](COMPATIBILITY.md)
- [Known issues](KNOWN_ISSUES.md)
- [Roadmap](ROADMAP.md)
- [Changelog](CHANGELOG.md)

Always verify the release JAR against `SHA256SUMS.txt` on the GitHub Release
page before installing it.

## Build from source

Use a Java 17 JDK:

```powershell
.\gradlew.bat clean build
```

The production JAR is written to
`forge-create-1.20.1/build/libs/steve-industrial-agent-0.1.0-alpha.1.jar`.
The build is configured for deterministic archive ordering and timestamps.
Minecraft, Forge, Create, DeceasedCraft and all other third-party mods are
user-installed dependencies and are never bundled in the project JAR.

## Safety model

Free text and model output cannot directly execute commands, arbitrary code or
unchecked coordinates. World reads and mutations are server-authoritative and
bounded. The public runtime fails closed unless the current instance, world
marker, backup, selected region, preview, readiness checks and confirmation all
match. High-risk nuclear and radiation automation is disabled.

## License and third-party software

Steve Industrial Agent is licensed under the MIT License. See [LICENSE](LICENSE),
[NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Third-party
loaders, mods, modpacks, launcher files, worlds, backups and account data are
not redistributed.
