package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.KineticRotationDirection;
import dev.stevecreate.agent.adapter.api.KineticSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeFingerprint;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.OptionalLong;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact Create 6.0.6 O(1) bridge over one already-loaded kinetic block entity. */
public final class Create606KineticReader {
    private Create606KineticReader() {
    }

    public static AdapterResult<KineticSnapshot> capture(
            ServerLevel level,
            BlockPos3i position,
            RuntimeFingerprint runtime) {
        BlockPos blockPos = new BlockPos(position.x(), position.y(), position.z());
        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if (!(blockEntity instanceof KineticBlockEntity kinetic)) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.KINETIC_COMPONENT_NOT_FOUND,
                    "Loaded position is not a Create 6.0.6 kinetic block entity: " + position);
        }
        if (kinetic.networkDirty || kinetic.needsSpeedUpdate()) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.LIFECYCLE_NOT_READY,
                    "Create 6.0.6 kinetic state has not settled at " + position);
        }

        CompoundTag tag = new CompoundTag();
        kinetic.saveAdditional(tag);
        OptionalLong networkId = OptionalLong.empty();
        int networkSize = 0;
        double capacity = 0;
        double load = 0;
        if (kinetic.hasNetwork()) {
            if (!tag.contains("Network", Tag.TAG_COMPOUND)) {
                return apiFailure(position, "missing Network compound");
            }
            CompoundTag network = tag.getCompound("Network");
            if (!network.contains("Id", Tag.TAG_ANY_NUMERIC)
                    || !network.contains("Stress", Tag.TAG_ANY_NUMERIC)
                    || !network.contains("Capacity", Tag.TAG_ANY_NUMERIC)
                    || !network.contains("Size", Tag.TAG_ANY_NUMERIC)) {
                return apiFailure(position, "incomplete Network totals");
            }
            networkId = OptionalLong.of(network.getLong("Id"));
            networkSize = network.getInt("Size");
            capacity = network.getFloat("Capacity");
            load = network.getFloat("Stress");
            if (networkSize < 1 || !Double.isFinite(capacity) || capacity < 0
                    || !Double.isFinite(load) || load < 0) {
                return apiFailure(position, "invalid Network totals");
            }
        }

        float signedActualSpeed = kinetic.getSpeed();
        if (!Float.isFinite(signedActualSpeed)) {
            return apiFailure(position, "non-finite actual speed");
        }
        ResourceLocation blockKey = ForgeRegistries.BLOCKS.getKey(kinetic.getBlockState().getBlock());
        if (blockKey == null) {
            return apiFailure(position, "unregistered kinetic block");
        }
        boolean stressEnabled = IRotate.StressImpact.isEnabled();
        boolean overstressed = kinetic.isOverStressed();
        if (overstressed != (stressEnabled && load > capacity)) {
            return new AdapterResult.Failure<>(
                    AdapterFailureCode.LIFECYCLE_NOT_READY,
                    "Create 6.0.6 stress state is changing at " + position);
        }

        double speedRpm = Math.abs((double) signedActualSpeed);
        return new AdapterResult.Success<>(new KineticSnapshot(
                level.getGameTime(),
                runtime,
                ResourceId.parse(level.dimension().location().toString()),
                new ResourceId(blockKey.getNamespace(), blockKey.getPath()),
                position,
                networkId,
                networkSize,
                speedRpm,
                KineticRotationDirection.fromSignedSpeed(signedActualSpeed),
                stressEnabled,
                capacity,
                load,
                overstressed));
    }

    private static AdapterResult<KineticSnapshot> apiFailure(BlockPos3i position, String detail) {
        return new AdapterResult.Failure<>(
                AdapterFailureCode.CREATE_API_FAILURE,
                "Create 6.0.6 kinetic data at " + position + ": " + detail);
    }
}
