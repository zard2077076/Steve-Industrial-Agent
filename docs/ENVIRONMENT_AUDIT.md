# External environment audit

Date: 2026-07-13. Scope: read-only inventory of the configured PCL2 compatibility instance. No mod, config or save was written.

## Reproduction

Set `PCL2_ROOT` locally, then run these read-only PowerShell queries:

```powershell
$instance = Join-Path $env:PCL2_ROOT '.minecraft\versions\DeceasedCraft_Beta 5.10.16'
$mods = Join-Path $instance 'mods'

Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' |
    Where-Object Name -Match '(?i)mekanism|mekanized|mekan'

Get-ChildItem -LiteralPath (Join-Path $instance 'config') -Recurse -File |
    Where-Object Name -Match '(?i)mekanism|mekan'

Get-ChildItem -LiteralPath (Join-Path $instance 'kubejs') -Recurse -File |
    Select-String -Pattern 'mekanism|mekanized' -CaseSensitive:$false
```

## Sanitized results

- Total mod JARs: 307.
- Mekanism core: absent.
- Mekanism Generators: absent.
- Mekanism Tools: absent.
- Mekanism Additions: absent.
- Mekanism-related config/defaultconfig: absent.
- Mekanism recipe overrides: absent.
- One KubeJS text match removes `bigreactors:reinforced_reactorfluidport_forge_mekanism_passive`; this is an Extreme Reactors compatibility block ID, not proof that Mekanism is installed.
- Create: `create-1.20.1-6.0.6.jar`.
- Forge runtime: 47.4.0.
- Minecraft: 1.20.1.

The external instance remains a compatibility reference only. Mekanism development dependencies and worlds must live in the isolated project environment.
