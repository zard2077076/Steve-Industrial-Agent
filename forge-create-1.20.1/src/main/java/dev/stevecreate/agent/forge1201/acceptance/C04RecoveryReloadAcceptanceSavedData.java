package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.plan.PlanAnchor;
import dev.stevecreate.agent.core.recovery.RecoveryCheckpointCodec;
import java.util.Arrays;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Isolated acceptance-only persistence for a C-04 checkpoint and its physical anchor. */
final class C04RecoveryReloadAcceptanceSavedData extends SavedData {
    static final String DATA_NAME = "steve_industrial_c04_recovery_acceptance";
    private static final String CHECKPOINT_KEY = "Checkpoint";
    private static final String ORIGIN_X_KEY = "OriginX";
    private static final String ORIGIN_Y_KEY = "OriginY";
    private static final String ORIGIN_Z_KEY = "OriginZ";
    private static final String ROTATION_KEY = "Rotation";

    private byte[] checkpoint;
    private PlanAnchor anchor;

    C04RecoveryReloadAcceptanceSavedData() {
        this(new byte[0], null);
    }

    private C04RecoveryReloadAcceptanceSavedData(byte[] checkpoint, PlanAnchor anchor) {
        this.checkpoint = copyCheckpoint(checkpoint);
        this.anchor = anchor;
    }

    static C04RecoveryReloadAcceptanceSavedData load(CompoundTag tag) {
        byte[] checkpoint = tag.getByteArray(CHECKPOINT_KEY);
        if (checkpoint.length == 0) {
            return new C04RecoveryReloadAcceptanceSavedData();
        }
        QuarterTurn rotation;
        try {
            rotation = QuarterTurn.valueOf(tag.getString(ROTATION_KEY));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Persisted C-04 rotation is invalid", exception);
        }
        return new C04RecoveryReloadAcceptanceSavedData(
                checkpoint,
                new PlanAnchor(
                        new BlockPos3i(
                                tag.getInt(ORIGIN_X_KEY),
                                tag.getInt(ORIGIN_Y_KEY),
                                tag.getInt(ORIGIN_Z_KEY)),
                        rotation));
    }

    boolean hasCheckpoint() {
        return checkpoint.length > 0;
    }

    byte[] checkpoint() {
        if (!hasCheckpoint()) {
            throw new IllegalStateException("C-04 recovery checkpoint is absent");
        }
        return checkpoint.clone();
    }

    PlanAnchor anchor() {
        if (anchor == null) {
            throw new IllegalStateException("C-04 recovery anchor is absent");
        }
        return anchor;
    }

    void checkpoint(byte[] encoded, PlanAnchor value) {
        byte[] copy = copyCheckpoint(encoded);
        if (copy.length == 0) {
            throw new IllegalArgumentException("C-04 recovery checkpoint is empty");
        }
        checkpoint = copy;
        anchor = java.util.Objects.requireNonNull(value, "value");
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putByteArray(CHECKPOINT_KEY, checkpoint);
        if (anchor != null) {
            tag.putInt(ORIGIN_X_KEY, anchor.position().x());
            tag.putInt(ORIGIN_Y_KEY, anchor.position().y());
            tag.putInt(ORIGIN_Z_KEY, anchor.position().z());
            tag.putString(ROTATION_KEY, anchor.rotation().name());
        }
        return tag;
    }

    private static byte[] copyCheckpoint(byte[] encoded) {
        if (encoded == null) {
            throw new NullPointerException("encoded");
        }
        if (encoded.length > RecoveryCheckpointCodec.MAX_ENCODED_BYTES + 32) {
            throw new IllegalArgumentException("C-04 recovery checkpoint exceeds bounds");
        }
        return Arrays.copyOf(encoded, encoded.length);
    }
}
