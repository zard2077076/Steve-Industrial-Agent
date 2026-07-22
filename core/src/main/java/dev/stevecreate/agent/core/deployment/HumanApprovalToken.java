package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;

/** Externally supplied structured approval. The gate never issues this token. */
public record HumanApprovalToken(
        String oneTimeTokenHash,
        HumanApprovalDecision decision,
        HumanApprovalAuthorizerType authorizerType,
        String authorizerIdentity,
        WorldEnvironmentType environmentClassification,
        String previewHash,
        String worldIdentity,
        String worldSnapshotFingerprint,
        String runtimeFingerprint,
        long reloadGeneration,
        DeploymentBoundingBox exactRegion,
        ResourceId exactTarget,
        long exactQuantity,
        int exactMutationBudget,
        DeploymentPolicy exactDeploymentPolicy,
        Instant expiresAt,
        String provenance) {
    public HumanApprovalToken {
        oneTimeTokenHash = hash(oneTimeTokenHash, "oneTimeTokenHash");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(authorizerType, "authorizerType");
        authorizerIdentity = text(authorizerIdentity, "authorizerIdentity");
        Objects.requireNonNull(environmentClassification, "environmentClassification");
        previewHash = hash(previewHash, "previewHash");
        worldIdentity = text(worldIdentity, "worldIdentity");
        worldSnapshotFingerprint = text(
                worldSnapshotFingerprint, "worldSnapshotFingerprint");
        runtimeFingerprint = text(runtimeFingerprint, "runtimeFingerprint");
        if (reloadGeneration < 0) {
            throw new IllegalArgumentException("reloadGeneration cannot be negative");
        }
        Objects.requireNonNull(exactRegion, "exactRegion");
        Objects.requireNonNull(exactTarget, "exactTarget");
        quantity(exactQuantity, "exactQuantity");
        mutation(exactMutationBudget, "exactMutationBudget");
        Objects.requireNonNull(exactDeploymentPolicy, "exactDeploymentPolicy");
        Objects.requireNonNull(expiresAt, "expiresAt");
        provenance = text(provenance, "provenance");

        if (authorizerType == HumanApprovalAuthorizerType.TEST_ONLY
                && (!"TEST_ONLY".equals(authorizerIdentity)
                        || environmentClassification != WorldEnvironmentType.ISOLATED_TEST_WORLD)) {
            throw new IllegalArgumentException(
                    "TEST_ONLY approvals require the explicit isolated test authorizer");
        }
        if (authorizerType == HumanApprovalAuthorizerType.HUMAN
                && "TEST_ONLY".equals(authorizerIdentity)) {
            throw new IllegalArgumentException("TEST_ONLY cannot masquerade as a human authorizer");
        }
    }

    static String text(String value, String name) {
        return WorldEnvironmentEvidence.text(value, name);
    }

    static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256 hex");
        }
        return value;
    }

    static long quantity(long value, String name) {
        if (value < 1 || value > 1_000_000_000_000L) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        return value;
    }

    static int mutation(int value, String name) {
        if (value < 0 || value > DeploymentPolicy.MAX_AFFECTED_BLOCKS) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        return value;
    }
}
