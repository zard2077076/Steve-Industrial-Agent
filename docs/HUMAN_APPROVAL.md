# Human approval gate

PW-08 treats approval as an externally supplied structured one-time token. `HumanApprovalGate` has no method that issues, approves or upgrades a token. It only compares a supplied token with current exact scope and atomically records one successful consumption.

The token binds its SHA-256 token hash, decision, authorizer, environment, preview hash, world identity, world-snapshot fingerprint, runtime fingerprint, reload generation, exact region, target, quantity, mutation budget, complete `DeploymentPolicy`, expiry and provenance. Any changed plan, world, runtime/reload, expanded or altered region, target/quantity, mutation ceiling, material/block policy budget or other policy field fails. Pending, rejected and expired tokens fail. Concurrent attempts against one gate produce exactly one acceptance.

Production approval is never auto-generated in this phase. Tests must explicitly use `HumanApprovalAuthorizerType.TEST_ONLY` with identity `TEST_ONLY`, and the token constructor accepts that authorizer only for `ISOLATED_TEST_WORLD`. Copying it to a formal request produces typed environment and TEST_ONLY-scope failures. A successful approval check remains evidence for PW-11, not readiness, a write permit or execution authority.

The in-memory consumed-token registry is intentionally coupled with exact reload generation. A later persistent deployment flow must preserve consumption evidence across process boundaries and PW-11 must compare the approval generation to the authoritative current reload generation; merely constructing a new gate is not permission to reuse a pre-reload token.

PW-09 separately requires explicit TEST_ONLY approval for its isolated backup plan. Passing PW-08 approval does not automatically approve a backup, and the backup verifier cannot issue either approval.
