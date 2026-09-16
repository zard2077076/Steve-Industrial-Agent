# Read-only deployment commands

PW-12 provides four server-authoritative dry-run views:

```text
/industrialagent deploy preview <resource_id> <quantity> [zero|clockwise_90|clockwise_270]
/industrialagent deploy risks <resource_id> <quantity> [zero|clockwise_90|clockwise_270]
/industrialagent deploy budget <resource_id> <quantity> [zero|clockwise_90|clockwise_270]
/industrialagent deploy readiness <resource_id> <quantity> [zero|clockwise_90|clockwise_270]
```

Orientation defaults to `zero`. The resource must be a legal namespaced ID and quantity must be positive and bounded by the command parser. Coordinates are not accepted; the Adapter chooses a bounded fixture anchor from the server command source. Standard acceptance reads loaded block states, while the explicitly disposable pack profile permits only its bounded read-only snapshot range.

Every successful report includes the target, quantity, preview hash, orientation, classified environment, affected bounds, materials, power demand/margin, risk, missing authorization/backup and readiness. It also explicitly reports `dryRun=true`, `formalWorldExecutable=false`, `worldMutation=false`, `sessionCreated=false`, `playerItemsConsumed=false`, `machineStarted=false`, `llmCalled=false` and `freeTextCoordinatesAccepted=false`.

These commands do not create a deployment-ready or execution-ready plan, reserve/withdraw items, place/remove blocks, start Create machinery, issue approval or call an LLM. Readiness remains BLOCKED because the report deliberately has no current region authorization, claim permission, human approval or verified backup/restore. Formal, unknown and forbidden worlds can never become executable through this surface.
