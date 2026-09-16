# Formal World Dry-Run Results

FS-10 proves the four-member artifact contract in synthetic formal fixtures. FS-12 does not relabel those hashes as real-world results. The accepted real survey records the following four outcomes:

| Kind | Target | Quantity | Verified recipe identity | Formal result |
|---|---|---:|---|---|
| gravel milling | `minecraft:gravel` | 3 | `create:milling/cobblestone` | BLOCKED; no preview hash |
| iron-sheet pressing | `create:iron_sheet` | 2 | `create:pressing/iron_ingot` | BLOCKED; no preview hash |
| pack custom milling | `immersiveengineering:dust_coke` | 4 | `create:kjs/4w2pibcjt4pwqhn60e2n67l1l` | BLOCKED; no preview hash |
| pack custom pressing | `apocalypsenow:can` | 3 | `create:kjs/af7rthcw104gweqz8mzvmcrw7` | BLOCKED; no preview hash |

Every row is blocked by `CLEAR_SPACE_NOT_VERIFIED`, `CLAIM_PERMISSION_UNKNOWN`, `CANDIDATE_PENDING_USER_SELECTION` and `FORMAL_WORLD_EXECUTION_FORBIDDEN`. No session, item/resource action, machine start, approval or formal mutation was attempted. This explicit unavailable result satisfies the Stage-A transition alternative; it does not satisfy deployment readiness.
