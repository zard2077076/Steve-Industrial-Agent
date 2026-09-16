package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.execution.readiness.ExecutionReadyPlan;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Exact bridge from a post-clearance site to the existing construction executor input.
 *
 * <p>The constructor is package-private so callers cannot manufacture this bridge without
 * passing {@link PreparedSiteExecutionGate}. It owns no executor or world mutation API.</p>
 */
public final class PreparedSiteExecutionAuthorization {
    private final String preparedSiteIdentity;
    private final String selectionHash;
    private final String worldIdentity;
    private final ResourceId dimension;
    private final String cleanSiteSnapshotHash;
    private final String planningSnapshotFingerprint;
    private final ExecutionReadyPlan executionReadyPlan;
    private final Set<BlockPos3i> coveredCells;
    private final Instant authorizedAt;
    private final Instant expiresAt;
    private final String evidenceHash;
    private final String provenance;

    PreparedSiteExecutionAuthorization(
            String preparedSiteIdentity,
            String selectionHash,
            String worldIdentity,
            ResourceId dimension,
            String cleanSiteSnapshotHash,
            String planningSnapshotFingerprint,
            ExecutionReadyPlan executionReadyPlan,
            Set<BlockPos3i> coveredCells,
            Instant authorizedAt,
            Instant expiresAt,
            String evidenceHash,
            String provenance) {
        this.preparedSiteIdentity = SitePreparationHashes.text(
                preparedSiteIdentity, "preparedSiteIdentity");
        this.selectionHash = SitePreparationHashes.hash(selectionHash, "selectionHash");
        this.worldIdentity = SitePreparationHashes.text(worldIdentity, "worldIdentity");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.cleanSiteSnapshotHash = SitePreparationHashes.hash(
                cleanSiteSnapshotHash, "cleanSiteSnapshotHash");
        this.planningSnapshotFingerprint = SitePreparationHashes.text(
                planningSnapshotFingerprint, "planningSnapshotFingerprint");
        this.executionReadyPlan = Objects.requireNonNull(
                executionReadyPlan, "executionReadyPlan");
        this.coveredCells = Set.copyOf(Objects.requireNonNull(coveredCells, "coveredCells"));
        if (this.coveredCells.isEmpty() || this.coveredCells.size() > 4_096) {
            throw new IllegalArgumentException("prepared-site execution cells are unbounded");
        }
        this.authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!this.authorizedAt.isBefore(this.expiresAt)) {
            throw new IllegalArgumentException("prepared-site execution expiry is invalid");
        }
        this.evidenceHash = SitePreparationHashes.hash(evidenceHash, "evidenceHash");
        this.provenance = SitePreparationHashes.text(provenance, "provenance");
    }

    public String preparedSiteIdentity() { return preparedSiteIdentity; }
    public String selectionHash() { return selectionHash; }
    public String worldIdentity() { return worldIdentity; }
    public ResourceId dimension() { return dimension; }
    public String cleanSiteSnapshotHash() { return cleanSiteSnapshotHash; }
    public String planningSnapshotFingerprint() { return planningSnapshotFingerprint; }
    public ExecutionReadyPlan executionReadyPlan() { return executionReadyPlan; }
    public Set<BlockPos3i> coveredCells() { return coveredCells; }
    public Instant authorizedAt() { return authorizedAt; }
    public Instant expiresAt() { return expiresAt; }
    public String evidenceHash() { return evidenceHash; }
    public String provenance() { return provenance; }
}
