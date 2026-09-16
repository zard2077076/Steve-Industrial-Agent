# Construction progress HUD (WIP)

`ConstructionProgressHud` renders a small client overlay from status packets
sent at most once per second unless evidence changes. It shows project target,
stage, active Bot count, exact cleared target count, mutation count and
delivered salvage count. It deliberately does not display an estimated percent
or exact completion time.

The overlay localizes player-facing labels in English and Simplified Chinese.
Typed phase/status codes remain visible as diagnostic evidence. Per-Bot task
descriptions are not shown because the current clearing snapshot does not
carry independently verified task text; inventing them would violate the HUD
evidence rule.

Manage Project opens `ConstructionProgressScreen`. Pause, Continue and Cancel
send typed server requests. Buttons enable only when the live server snapshot
proves the action is available; Cancel remains disabled while a real drop or
carried salvage is undelivered. Recovery messages explain that re-preview and
approval are required rather than claiming automatic resume.

After the clean rescan the HUD shows
`CONSTRUCTION_MATERIAL_SOURCE_REQUIRED`. Construction phase/task/Bot evidence
and `CompletionReportScreen` remain pending until a player-owned material
source, exact MaterialLedger debit/refund and existing executor handoff are
implemented. This checkpoint must remain WIP.
