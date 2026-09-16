# Claim and permission boundary

PW-10 defines a loader-neutral boundary without claiming support for every land-claim mod. `PermissionQuery` contains query ID, typed operation, actor, exact region, world/environment/dimension, source, timestamp, generation and fingerprint. `PermissionEvidence` retains the complete query plus UNKNOWN/ALLOWED/DENIED/NOT_INSTALLED, Adapter ID, verification state, source, observed time/generation/fingerprint and provenance.

Only exact current VERIFIED ALLOWED evidence in an isolated or development environment can report construction permission. UNKNOWN, DENIED, NOT_INSTALLED, stale generation/fingerprint/time and every formal/unknown/forbidden environment return false. This helper is still evidence only; PW-11 must join it with policy, region, approval, backup and all other gates.

`ClaimAdapter` is a non-sealed `adapter-api` interface. The shipped generic implementations are isolated-test allow, explicit deny, unknown and verified-not-installed. A test-source `thirdparty:claims-fixture` implementation supplies its own ID and logic through the same interface, proving core does not select a known mod name.

Retained disposable DeceasedCraft evidence discovers `openpartiesandclaims` 0.25.8: the source manifest contains its JAR and five default configs, the runtime inventory names the loaded mod, and `work/logs/deceasedcraft-isolated-server-20260717-202714.log` records initialization, claim loading and `permission_api` selection. This proves presence only. PW-10A tracks API review and a versioned read-only Adapter in disposable fixtures; no OPAC class, claim data or formal API was accessed here.
