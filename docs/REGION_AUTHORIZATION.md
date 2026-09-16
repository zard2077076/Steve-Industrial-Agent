# Region authorization

PW-07 models exact region scope as immutable loader-neutral evidence. It does not issue a filesystem capability, consume a token, construct a deployment-ready plan or call a world/session/handler.

`RegionAuthorization` binds authorization ID, exact environment/world/dimension, inclusive bounds, optional owner identity, authorizer, allowed operations, maximum block mutations, expiry, preview hash, world-snapshot and runtime fingerprints, one-time/use state, approval, revocation and provenance. Owner absence remains explicit; core never invents an owner. The 13 operations are `READ_ONLY_SCAN`, `DRY_RUN`, placement/removal/replacement, container access, withdrawal/insertion, power/logistics connection, machine start, cleanup and rollback.

`RegionAuthorizationService` compares an exact request and returns ordered typed failures. Nested bounds are allowed; any world, environment, dimension, owner, operation, mutation, preview, snapshot or runtime expansion/mismatch fails. Expired, pending/rejected, revoked or consumed one-time evidence also fails. A success is only scope evidence for PW-11 and is not a write permit.

Formal, unknown and forbidden records can contain only `READ_ONLY_SCAN` and `DRY_RUN`, must have zero mutation budget and cannot be `APPROVED`. Formal checks always add `FORMAL_WORLD_EXECUTION_FORBIDDEN`. This phase did not scan or open a formal world; a future read-only formal survey still requires a separate explicit user instruction after the remaining gates exist.

PW-08 adds a separate one-time human approval token over the exact region and deployment scope. It does not turn a PW-07 scope result into approval, and neither result constructs a permit.
