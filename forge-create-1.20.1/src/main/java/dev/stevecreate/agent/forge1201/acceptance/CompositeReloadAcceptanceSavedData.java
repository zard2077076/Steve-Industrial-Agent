package dev.stevecreate.agent.forge1201.acceptance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Carries the write phase's exact expectations into the reading JVM.
 *
 * <p>A reload gate that only re-reads the order envelope proves the envelope paused,
 * not that nothing was withdrawn twice.  The write phase records what the player's own
 * chest held and how much the ledger had taken at the moment it exited, so the reading
 * process can compare against a number it did not compute itself.</p>
 */
public final class CompositeReloadAcceptanceSavedData extends SavedData {
    private static final String NAME = "steve_industrial_composite_reload_acceptance";

    private UUID projectId;
    private String orderType = "";
    private String mode = "";
    private int sourceX;
    private int sourceY;
    private int sourceZ;
    private long withdrawnTotal;
    private String phaseAtExit = "";
    private String stageAtExit = "";
    private long generationAtExit;
    private final Map<String, Long> sourceContents = new LinkedHashMap<>();

    public static CompositeReloadAcceptanceSavedData forLevel(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                CompositeReloadAcceptanceSavedData::load,
                CompositeReloadAcceptanceSavedData::new,
                NAME);
    }

    private static CompositeReloadAcceptanceSavedData load(CompoundTag tag) {
        CompositeReloadAcceptanceSavedData data = new CompositeReloadAcceptanceSavedData();
        if (tag.hasUUID("projectId")) data.projectId = tag.getUUID("projectId");
        data.orderType = tag.getString("orderType");
        data.mode = tag.getString("mode");
        data.sourceX = tag.getInt("sourceX");
        data.sourceY = tag.getInt("sourceY");
        data.sourceZ = tag.getInt("sourceZ");
        data.withdrawnTotal = tag.getLong("withdrawnTotal");
        data.phaseAtExit = tag.getString("phaseAtExit");
        data.stageAtExit = tag.getString("stageAtExit");
        data.generationAtExit = tag.getLong("generationAtExit");
        ListTag contents = tag.getList("sourceContents", Tag.TAG_COMPOUND);
        for (int index = 0; index < contents.size(); index++) {
            CompoundTag entry = contents.getCompound(index);
            data.sourceContents.put(entry.getString("item"), entry.getLong("count"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (projectId != null) tag.putUUID("projectId", projectId);
        tag.putString("orderType", orderType);
        tag.putString("mode", mode);
        tag.putInt("sourceX", sourceX);
        tag.putInt("sourceY", sourceY);
        tag.putInt("sourceZ", sourceZ);
        tag.putLong("withdrawnTotal", withdrawnTotal);
        tag.putString("phaseAtExit", phaseAtExit);
        tag.putString("stageAtExit", stageAtExit);
        tag.putLong("generationAtExit", generationAtExit);
        ListTag contents = new ListTag();
        sourceContents.forEach((item, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("item", item);
            entry.putLong("count", count);
            contents.add(entry);
        });
        tag.put("sourceContents", contents);
        return tag;
    }

    public void record(
            UUID projectId,
            String orderType,
            String mode,
            int sourceX,
            int sourceY,
            int sourceZ,
            long withdrawnTotal,
            String phaseAtExit,
            String stageAtExit,
            long generationAtExit,
            Map<String, Long> sourceContents) {
        this.projectId = projectId;
        this.orderType = orderType;
        this.mode = mode;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceZ = sourceZ;
        this.withdrawnTotal = withdrawnTotal;
        this.phaseAtExit = phaseAtExit;
        this.stageAtExit = stageAtExit;
        this.generationAtExit = generationAtExit;
        this.sourceContents.clear();
        this.sourceContents.putAll(sourceContents);
        setDirty();
    }

    public boolean hasCheckpoint() { return projectId != null; }

    public UUID projectId() { return projectId; }

    public String orderType() { return orderType; }

    public String mode() { return mode; }

    public int sourceX() { return sourceX; }

    public int sourceY() { return sourceY; }

    public int sourceZ() { return sourceZ; }

    public long withdrawnTotal() { return withdrawnTotal; }

    public String phaseAtExit() { return phaseAtExit; }

    public String stageAtExit() { return stageAtExit; }

    public long generationAtExit() { return generationAtExit; }

    public Map<String, Long> sourceContents() { return Map.copyOf(sourceContents); }
}
