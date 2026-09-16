# Site survey

`SiteSurvey` assembles bounded server-authoritative observations into an
immutable `SiteSurveySnapshot`. The scan is limited to the selected region,
plan footprint, required clearance and Bot/maintenance corridors, with at most
4,096 findings.

Each observation carries position, registered block identity, exact state
fingerprint, BlockEntity/container/inventory/machine flags, natural-placement
evidence, hardness/tool/drop expectation, fluid/environment risk, protection
state and evidence source. No player inventory, ender chest, chat, account data
or out-of-region block is read.

The canonical snapshot hash binds selection hash, physical plan hash, bounds
and every classified finding/state.
