package dev.stevecreate.agent.forge1201.acceptance;

import dev.stevecreate.agent.core.recovery.RecoveryCheckpointCodec;
import java.util.Arrays;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Repository-fixture-only SavedData container for one encoded recovery checkpoint. */
final class RecoveryReloadAcceptanceSavedData extends SavedData {
    static final String DATA_NAME = "steve_industrial_recovery_acceptance";
    private static final String CHECKPOINT_KEY = "Checkpoint";

    private byte[] checkpoint;

    RecoveryReloadAcceptanceSavedData() {
        this(new byte[0]);
    }

    private RecoveryReloadAcceptanceSavedData(byte[] checkpoint) {
        this.checkpoint = copyCheckpoint(checkpoint);
    }

    static RecoveryReloadAcceptanceSavedData load(CompoundTag tag) {
        return new RecoveryReloadAcceptanceSavedData(tag.getByteArray(CHECKPOINT_KEY));
    }

    boolean hasCheckpoint() {
        return checkpoint.length > 0;
    }

    byte[] checkpoint() {
        if (!hasCheckpoint()) {
            throw new IllegalStateException("Recovery acceptance checkpoint is absent");
        }
        return checkpoint.clone();
    }

    void checkpoint(byte[] encoded) {
        byte[] copy = copyCheckpoint(encoded);
        if (copy.length == 0) {
            throw new IllegalArgumentException("Recovery acceptance checkpoint is empty");
        }
        checkpoint = copy;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putByteArray(CHECKPOINT_KEY, checkpoint);
        return tag;
    }

    private static byte[] copyCheckpoint(byte[] encoded) {
        if (encoded == null) {
            throw new NullPointerException("encoded");
        }
        if (encoded.length > RecoveryCheckpointCodec.MAX_ENCODED_BYTES + 32) {
            throw new IllegalArgumentException("Recovery acceptance checkpoint exceeds bounds");
        }
        return Arrays.copyOf(encoded, encoded.length);
    }
}
