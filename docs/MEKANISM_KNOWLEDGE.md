# Mekanism knowledge

## Observed environment

The external DeceasedCraft instance contains no Mekanism core, Generators, Tools, Additions, Mekanism config, or Mekanism recipe override. The project therefore treats Mekanism as optional and will use an isolated development profile for tests.

## Resource model

Mekanism support must distinguish Forge Energy, items, fluids, chemicals and heat. Ports include direction, side configuration, input/output role, auto-eject, redstone mode and capacity. Runtime recipe data overrides assumptions and pack defaults.

M0 begins with safe registry/runtime detection. Later adapters read real capabilities, internal buffers, progress, errors and network membership for the pinned 1.20.1 version. Old gas-only terminology must not leak into the generic chemical model.

Nuclear/radiation systems are explicitly disabled; see `docs/ARCHITECTURE.md` and `docs/DECISIONS.md`.
