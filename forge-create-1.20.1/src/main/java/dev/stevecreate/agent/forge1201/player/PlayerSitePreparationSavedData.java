package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.siteprep.AnchorSource;
import dev.stevecreate.agent.core.siteprep.ConfirmedSiteSelection;
import dev.stevecreate.agent.core.siteprep.ObstacleClassification;
import dev.stevecreate.agent.core.siteprep.ObstacleFinding;
import dev.stevecreate.agent.core.siteprep.PlacementAnchor;
import dev.stevecreate.agent.core.siteprep.PreparedConstructionSite;
import dev.stevecreate.agent.core.siteprep.SalvageEntry;
import dev.stevecreate.agent.core.siteprep.SalvageLedger;
import dev.stevecreate.agent.core.siteprep.SiteFacing;
import dev.stevecreate.agent.core.siteprep.TerrainMutationEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable player-workflow site preparation evidence.
 *
 * <p>A player project is persistent while its site preparation state used to live only in a
 * static map. A restart emptied the map and left the persisted project in a stage whose every
 * exit asked for a prepared site that no longer existed. This stores the exact evidence that
 * bridge needs — the confirmed selection, the authorized salvage container and, once the
 * post-clearance rescan has minted it, the prepared site itself.
 *
 * <p>Nothing here grants authority. The stored record carries its own freshness and identity,
 * and is re-checked against the live world on restore exactly as the in-memory copy was; an
 * expired or drifted record is refused by the same gate rather than renewed by this class.</p>
 */
public final class PlayerSitePreparationSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_player_site_preparation";
    public static final int SCHEMA = 1;
    private static final int MAXIMUM_EVIDENCE = 4_096;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public static PlayerSitePreparationSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                PlayerSitePreparationSavedData::load, PlayerSitePreparationSavedData::new,
                DATA_NAME);
    }

    public static PlayerSitePreparationSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema != 0 && schema != SCHEMA) {
            throw new IllegalStateException("Unsupported player site preparation schema " + schema);
        }
        PlayerSitePreparationSavedData data = new PlayerSitePreparationSavedData();
        for (Tag raw : root.getList("Sites", Tag.TAG_COMPOUND)) {
            try {
                Entry entry = decodeEntry((CompoundTag) raw);
                data.entries.put(entry.playerId(), entry);
            } catch (RuntimeException malformed) {
                // Site evidence that no longer parses cannot be repaired into authority.
                // Dropping it returns the owning project to "no prepared site", which the
                // workflow already refuses safely, instead of restoring a half-record.
            }
        }
        return data;
    }

    public Optional<Entry> entry(UUID playerId) {
        return Optional.ofNullable(entries.get(playerId));
    }

    public void put(Entry entry) {
        entries.put(entry.playerId(), entry);
        setDirty();
    }

    public void remove(UUID playerId) {
        if (entries.remove(playerId) != null) setDirty();
    }

    public void removeProject(UUID projectId) {
        if (entries.values().removeIf(entry -> entry.projectId().equals(projectId))) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag values = new ListTag();
        entries.values().stream()
                .sorted(Comparator.comparing(entry -> entry.playerId().toString()))
                .forEach(entry -> values.add(encodeEntry(entry)));
        root.put("Sites", values);
        return root;
    }

    private static CompoundTag encodeEntry(Entry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerId", entry.playerId());
        tag.putUUID("ProjectId", entry.projectId());
        tag.putString("SessionIdentity", entry.sessionIdentity());
        tag.put("Selection", encodeSelection(entry.selection()));
        tag.put("Salvage", encodeSalvage(entry.salvage()));
        tag.putBoolean("HasPrepared", entry.prepared() != null);
        if (entry.prepared() != null) tag.put("Prepared", encodePrepared(entry.prepared()));
        tag.putLong("UpdatedAt", entry.updatedAt());
        return tag;
    }

    private static Entry decodeEntry(CompoundTag tag) {
        return new Entry(
                tag.getUUID("PlayerId"),
                tag.getUUID("ProjectId"),
                tag.getString("SessionIdentity"),
                decodeSelection(tag.getCompound("Selection")),
                decodeSalvage(tag.getCompound("Salvage")),
                tag.getBoolean("HasPrepared") ? decodePrepared(tag.getCompound("Prepared")) : null,
                tag.getLong("UpdatedAt"));
    }

    private static CompoundTag encodeSelection(ConfirmedSiteSelection selection) {
        CompoundTag tag = new CompoundTag();
        tag.put("Anchor", encodeAnchor(selection.anchor()));
        tag.putString("Facing", selection.facing().name());
        tag.put("Bounds", encodeBounds(selection.authorizedBounds()));
        tag.putString("SessionIdentity", selection.sessionIdentity());
        putInstant(tag, "ConfirmedAt", selection.confirmedAt());
        putInstant(tag, "ExpiresAt", selection.expiresAt());
        tag.putString("SelectionHash", selection.selectionHash());
        return tag;
    }

    private static ConfirmedSiteSelection decodeSelection(CompoundTag tag) {
        return new ConfirmedSiteSelection(
                decodeAnchor(tag.getCompound("Anchor")),
                SiteFacing.valueOf(tag.getString("Facing")),
                decodeBounds(tag.getCompound("Bounds")),
                tag.getString("SessionIdentity"),
                instant(tag, "ConfirmedAt"),
                instant(tag, "ExpiresAt"),
                tag.getString("SelectionHash"));
    }

    private static CompoundTag encodeAnchor(PlacementAnchor anchor) {
        CompoundTag tag = new CompoundTag();
        tag.putString("WorldIdentity", anchor.worldIdentity());
        tag.putString("Dimension", anchor.dimension().toString());
        putPosition(tag, anchor.position());
        tag.putString("PlayerIdentity", anchor.playerIdentity());
        putInstant(tag, "SelectedAt", anchor.selectedAt());
        tag.putString("Source", anchor.source().name());
        tag.putString("SelectionHash", anchor.selectionHash());
        return tag;
    }

    private static PlacementAnchor decodeAnchor(CompoundTag tag) {
        return new PlacementAnchor(
                tag.getString("WorldIdentity"),
                ResourceId.parse(tag.getString("Dimension")),
                position(tag),
                tag.getString("PlayerIdentity"),
                instant(tag, "SelectedAt"),
                AnchorSource.valueOf(tag.getString("Source")),
                tag.getString("SelectionHash"));
    }

    private static CompoundTag encodeSalvage(SalvageBinding salvage) {
        CompoundTag tag = new CompoundTag();
        tag.putString("WorldIdentity", salvage.worldIdentity());
        tag.putString("Dimension", salvage.dimension().toString());
        putPosition(tag, salvage.position());
        tag.putString("StateFingerprint", salvage.stateFingerprint());
        tag.putString("PlayerIdentity", salvage.playerIdentity());
        tag.putString("Identity", salvage.identity());
        return tag;
    }

    private static SalvageBinding decodeSalvage(CompoundTag tag) {
        return new SalvageBinding(
                tag.getString("WorldIdentity"),
                ResourceId.parse(tag.getString("Dimension")),
                position(tag),
                tag.getString("StateFingerprint"),
                tag.getString("PlayerIdentity"),
                tag.getString("Identity"));
    }

    private static CompoundTag encodePrepared(PreparedConstructionSite prepared) {
        CompoundTag tag = new CompoundTag();
        tag.putString("PreparedSiteIdentity", prepared.preparedSiteIdentity());
        tag.putString("WorldIdentity", prepared.worldIdentity());
        tag.putString("Dimension", prepared.dimension().toString());
        tag.put("Anchor", encodeAnchor(prepared.anchor()));
        tag.putString("Facing", prepared.facing().name());
        tag.putString("PlanHash", prepared.planHash());
        tag.putString("CleanSiteSnapshotHash", prepared.cleanSiteSnapshotHash());
        tag.putString("TerrainGraphIdentity", prepared.terrainPreparationGraphIdentity());
        tag.put("SalvageLedger", encodeLedger(prepared.salvageLedger()));
        ListTag findings = new ListTag();
        prepared.remainingProtectedFindings().forEach(value -> findings.add(encodeFinding(value)));
        tag.put("RemainingProtectedFindings", findings);
        ListTag mutations = new ListTag();
        prepared.mutationEvidence().forEach(value -> mutations.add(encodeMutation(value)));
        tag.put("MutationEvidence", mutations);
        putInstant(tag, "PreparedAt", prepared.preparedAt());
        putInstant(tag, "ExpiresAt", prepared.expiresAt());
        return tag;
    }

    private static PreparedConstructionSite decodePrepared(CompoundTag tag) {
        ListTag rawFindings = tag.getList("RemainingProtectedFindings", Tag.TAG_COMPOUND);
        ListTag rawMutations = tag.getList("MutationEvidence", Tag.TAG_COMPOUND);
        if (rawFindings.size() > MAXIMUM_EVIDENCE || rawMutations.size() > MAXIMUM_EVIDENCE) {
            throw new IllegalArgumentException("prepared-site evidence is unbounded");
        }
        List<ObstacleFinding> findings = new ArrayList<>();
        for (Tag raw : rawFindings) findings.add(decodeFinding((CompoundTag) raw));
        List<TerrainMutationEvidence> mutations = new ArrayList<>();
        for (Tag raw : rawMutations) mutations.add(decodeMutation((CompoundTag) raw));
        return new PreparedConstructionSite(
                tag.getString("PreparedSiteIdentity"),
                tag.getString("WorldIdentity"),
                ResourceId.parse(tag.getString("Dimension")),
                decodeAnchor(tag.getCompound("Anchor")),
                SiteFacing.valueOf(tag.getString("Facing")),
                tag.getString("PlanHash"),
                tag.getString("CleanSiteSnapshotHash"),
                tag.getString("TerrainGraphIdentity"),
                decodeLedger(tag.getCompound("SalvageLedger")),
                List.copyOf(findings),
                List.copyOf(mutations),
                instant(tag, "PreparedAt"),
                instant(tag, "ExpiresAt"));
    }

    private static CompoundTag encodeLedger(SalvageLedger ledger) {
        CompoundTag tag = new CompoundTag();
        tag.putString("LedgerIdentity", ledger.ledgerIdentity());
        tag.putString("DestinationIdentity", ledger.destinationIdentity());
        ListTag values = new ListTag();
        ledger.entries().forEach(entry -> {
            CompoundTag value = new CompoundTag();
            value.putString("ObstacleIdentity", entry.obstacleIdentity());
            value.putString("ResourceId", entry.resourceId().toString());
            value.putInt("ExpectedCount", entry.expectedCount());
            value.putInt("CollectedCount", entry.collectedCount());
            value.putInt("DeliveredCount", entry.deliveredCount());
            value.putString("ExecutorIdentity", entry.executorIdentity());
            values.add(value);
        });
        tag.put("Entries", values);
        return tag;
    }

    private static SalvageLedger decodeLedger(CompoundTag tag) {
        ListTag raw = tag.getList("Entries", Tag.TAG_COMPOUND);
        if (raw.size() > MAXIMUM_EVIDENCE) {
            throw new IllegalArgumentException("salvage ledger is unbounded");
        }
        List<SalvageEntry> values = new ArrayList<>();
        for (Tag element : raw) {
            CompoundTag value = (CompoundTag) element;
            values.add(new SalvageEntry(
                    value.getString("ObstacleIdentity"),
                    ResourceId.parse(value.getString("ResourceId")),
                    value.getInt("ExpectedCount"),
                    value.getInt("CollectedCount"),
                    value.getInt("DeliveredCount"),
                    value.getString("ExecutorIdentity")));
        }
        return new SalvageLedger(tag.getString("LedgerIdentity"),
                tag.getString("DestinationIdentity"), List.copyOf(values));
    }

    private static CompoundTag encodeFinding(ObstacleFinding finding) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ObstacleId", finding.obstacleId());
        putPosition(tag, finding.position());
        tag.putString("BlockId", finding.blockId().toString());
        tag.putString("BlockStateFingerprint", finding.blockStateFingerprint());
        tag.putBoolean("BlockEntity", finding.blockEntity());
        tag.putBoolean("Container", finding.container());
        tag.putBoolean("HasInventory", finding.hasInventory());
        tag.putBoolean("Machine", finding.machine());
        tag.putBoolean("NaturalCandidate", finding.naturalCandidate());
        tag.putDouble("Hardness", finding.hardness());
        tag.putString("ToolRequirement", finding.toolRequirement());
        tag.putString("DropsExpectation", finding.dropsExpectation());
        tag.putBoolean("FluidRisk", finding.fluidRisk());
        tag.putString("Classification", finding.classification().name());
        tag.putInt("Confidence", finding.confidence());
        tag.putString("EvidenceSource", finding.evidenceSource());
        tag.putString("Reason", finding.reason());
        return tag;
    }

    private static ObstacleFinding decodeFinding(CompoundTag tag) {
        return new ObstacleFinding(
                tag.getString("ObstacleId"),
                position(tag),
                ResourceId.parse(tag.getString("BlockId")),
                tag.getString("BlockStateFingerprint"),
                tag.getBoolean("BlockEntity"),
                tag.getBoolean("Container"),
                tag.getBoolean("HasInventory"),
                tag.getBoolean("Machine"),
                tag.getBoolean("NaturalCandidate"),
                tag.getDouble("Hardness"),
                tag.getString("ToolRequirement"),
                tag.getString("DropsExpectation"),
                tag.getBoolean("FluidRisk"),
                ObstacleClassification.valueOf(tag.getString("Classification")),
                tag.getInt("Confidence"),
                tag.getString("EvidenceSource"),
                tag.getString("Reason"));
    }

    private static CompoundTag encodeMutation(TerrainMutationEvidence evidence) {
        CompoundTag tag = new CompoundTag();
        putPosition(tag, evidence.position());
        tag.putString("BeforeStateFingerprint", evidence.beforeStateFingerprint());
        tag.putString("AfterStateFingerprint", evidence.afterStateFingerprint());
        tag.putString("ExecutorIdentity", evidence.executorIdentity());
        putInstant(tag, "ObservedAt", evidence.observedAt());
        return tag;
    }

    private static TerrainMutationEvidence decodeMutation(CompoundTag tag) {
        return new TerrainMutationEvidence(
                position(tag),
                tag.getString("BeforeStateFingerprint"),
                tag.getString("AfterStateFingerprint"),
                tag.getString("ExecutorIdentity"),
                instant(tag, "ObservedAt"));
    }

    private static CompoundTag encodeBounds(DeploymentBoundingBox bounds) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("MinX", bounds.minimum().x());
        tag.putInt("MinY", bounds.minimum().y());
        tag.putInt("MinZ", bounds.minimum().z());
        tag.putInt("MaxX", bounds.maximum().x());
        tag.putInt("MaxY", bounds.maximum().y());
        tag.putInt("MaxZ", bounds.maximum().z());
        return tag;
    }

    private static DeploymentBoundingBox decodeBounds(CompoundTag tag) {
        return new DeploymentBoundingBox(
                new BlockPos3i(tag.getInt("MinX"), tag.getInt("MinY"), tag.getInt("MinZ")),
                new BlockPos3i(tag.getInt("MaxX"), tag.getInt("MaxY"), tag.getInt("MaxZ")));
    }

    private static void putPosition(CompoundTag tag, BlockPos3i position) {
        tag.putInt("X", position.x());
        tag.putInt("Y", position.y());
        tag.putInt("Z", position.z());
    }

    private static BlockPos3i position(CompoundTag tag) {
        return new BlockPos3i(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }

    // Evidence timestamps are compared against each other and against now by the execution
    // gate. Storing epoch seconds and nanos keeps a restored record identical to the one that
    // was written instead of quietly rounding a freshness boundary.
    private static void putInstant(CompoundTag tag, String key, Instant instant) {
        tag.putLong(key + "Seconds", instant.getEpochSecond());
        tag.putInt(key + "Nanos", instant.getNano());
    }

    private static Instant instant(CompoundTag tag, String key) {
        return Instant.ofEpochSecond(tag.getLong(key + "Seconds"), tag.getInt(key + "Nanos"));
    }

    /** Location-only binding for the authorized salvage container. */
    public record SalvageBinding(
            String worldIdentity,
            ResourceId dimension,
            BlockPos3i position,
            String stateFingerprint,
            String playerIdentity,
            String identity) {
        public SalvageBinding {
            if (worldIdentity == null || worldIdentity.isBlank() || dimension == null
                    || position == null || stateFingerprint == null
                    || !stateFingerprint.matches("[0-9a-f]{64}")
                    || playerIdentity == null || playerIdentity.isBlank()
                    || identity == null || identity.isBlank() || identity.length() > 160) {
                throw new IllegalArgumentException("invalid salvage binding");
            }
        }
    }

    /** One player's durable site preparation evidence for one project. */
    public record Entry(
            UUID playerId,
            UUID projectId,
            String sessionIdentity,
            ConfirmedSiteSelection selection,
            SalvageBinding salvage,
            PreparedConstructionSite prepared,
            long updatedAt) {
        public Entry {
            if (playerId == null || projectId == null || sessionIdentity == null
                    || sessionIdentity.isBlank() || sessionIdentity.length() > 160
                    || selection == null || salvage == null || updatedAt < 0) {
                throw new IllegalArgumentException("invalid player site preparation entry");
            }
            if (!selection.anchor().dimension().equals(salvage.dimension())) {
                throw new IllegalArgumentException("salvage binding is in another dimension");
            }
            if (prepared != null
                    && (!prepared.anchor().equals(selection.anchor())
                            || prepared.facing() != selection.facing())) {
                throw new IllegalArgumentException("prepared site does not match its selection");
            }
        }
    }
}
