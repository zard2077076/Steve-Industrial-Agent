# Third-party notices

Steve Industrial Agent is licensed under MIT. The release JAR contains the
project's own classes plus its loader-neutral `core` and `adapter-api` classes.
It does not bundle Minecraft, Forge, Create, Ponder, Flywheel, Registrate,
MixinExtras, DeceasedCraft or another mod JAR.

| Component | Version | Relationship | Upstream license/status |
|---|---|---|---|
| Minecraft | 1.20.1 | User-installed runtime; never redistributed | Proprietary; Mojang/Microsoft terms |
| Minecraft Forge | 47.4.10 build, 47.4.0 target | User-installed loader | LGPL-2.1 with upstream notices |
| Create | 6.0.6-150 | User-installed optional runtime mod | Upstream terms; not redistributed here |
| Ponder | 1.0.80 | Create runtime dependency | Upstream terms; not redistributed here |
| Flywheel | 1.0.4 | Create runtime dependency | Upstream terms; not redistributed here |
| Registrate | MC1.20-1.3.3 | Create runtime dependency | MPL-2.0; not redistributed here |
| MixinExtras | 0.4.1 | Integration dependency | MIT; not redistributed here |
| JUnit Jupiter | 5.10.2 | Test only | EPL-2.0; not in release JAR |
| AssertJ Core | 3.25.3 | Test only | Apache-2.0; not in release JAR |

DeceasedCraft and every mod, asset, configuration, script and world belonging
to that pack remain outside this project's distribution. Users obtain the
official pack and its dependencies themselves from authorized sources. This is
an inventory and boundary statement, not legal advice or a grant of rights to
redistribute third-party files.
