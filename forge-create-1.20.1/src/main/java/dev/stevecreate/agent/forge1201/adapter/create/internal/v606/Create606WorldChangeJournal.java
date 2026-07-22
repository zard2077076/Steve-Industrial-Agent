package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.stevecreate.agent.core.execution.SessionWorldChangeReference;
import dev.stevecreate.agent.core.execution.StepRunnerContext;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.InjectedResourceChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.IrreversibleProcessingChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.JournalData;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackOperation;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackPlan;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackReport;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackWarning;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.RollbackWarningCode;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact 1.20.1 snapshot and conservative rollback bridge for v606 Create handlers. */
final class Create606WorldChangeJournal {
    private static final ResourceId BLOCK_ENTITY_SNBT_SCHEMA =
            new ResourceId("minecraft", "snbt/block_entity");

    private final ServerLevel level;
    private final ResourceId sessionId;
    private final List<SessionWorldChangeReference> invocationReferences = new ArrayList<>();
    private WorldChangeJournal journal;
    private int changeSequence;

    Create606WorldChangeJournal(ServerLevel level, ResourceId sessionId) {
        this(level, sessionId, WorldChangeJournal.empty(sessionId));
    }

    Create606WorldChangeJournal(
            ServerLevel level,
            ResourceId sessionId,
            WorldChangeJournal existingJournal) {
        this.level = Objects.requireNonNull(level, "level");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.journal = Objects.requireNonNull(existingJournal, "existingJournal");
        if (!sessionId.equals(existingJournal.sessionId())) {
            throw new IllegalArgumentException(
                    "Recovered journal does not belong to the recovered session");
        }
        this.changeSequence = existingJournal.entries().size();
    }

    WorldChangeJournal snapshot() {
        return journal;
    }

    void stabilizeBlockEntityAfterStates() {
        Map<BlockPos3i, WorldBlockSnapshot> current = new LinkedHashMap<>();
        for (BlockPos3i position : journal.modifiedPositions()) {
            if (!level.hasChunk(position.x() >> 4, position.z() >> 4)) {
                throw new IllegalStateException(
                        "Recovery checkpoint target chunk is not loaded at " + position);
            }
            current.put(position, capture(position(position)));
        }
        journal = journal.stabilizeBlockEntityAfterStates(current);
    }

    void beginInvocation() {
        if (!invocationReferences.isEmpty()) {
            throw new IllegalStateException("Previous world-change references were not drained");
        }
    }

    WorldBlockSnapshot capture(BlockPos position) {
        Objects.requireNonNull(position, "position");
        BlockState state = level.getBlockState(position);
        ResourceLocation blockKey = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockKey == null) {
            throw new IllegalStateException("World block has no registered identity at " + position);
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), propertyValue(state, property));
        }
        BlockEntity blockEntity = level.getBlockEntity(position);
        Optional<JournalData> data = blockEntity == null
                ? Optional.empty()
                : Optional.of(new JournalData(
                        BLOCK_ENTITY_SNBT_SCHEMA,
                        blockEntity.saveWithFullMetadata().toString()));
        WorldBlockSnapshot snapshot = new WorldBlockSnapshot(
                ResourceId.parse(blockKey.toString()),
                properties,
                data);
        return blockEntity instanceof KineticBlockEntity
                ? Create606RecoverySnapshotNormalizer.normalizeKineticSnapshot(snapshot)
                : snapshot;
    }

    void recordBlockChange(
            StepRunnerContext context,
            BlockPos position,
            WorldBlockSnapshot before) {
        recordBlockChange(context.stepId(), context.gameTick(), position, before);
    }

    void recordBlockChange(
            ResourceId sourceStepId,
            long gameTick,
            BlockPos position,
            WorldBlockSnapshot before) {
        WorldBlockSnapshot after = capture(position);
        if (before.equals(after)) {
            return;
        }
        append(new BlockChange(
                nextChangeId(),
                sessionId,
                sourceStepId,
                gameTick,
                position(position),
                before,
                after));
    }

    void recordInjectedResource(
            StepRunnerContext context,
            BlockPos position,
            ProcessResource resource) {
        append(new InjectedResourceChange(
                nextChangeId(),
                sessionId,
                context.stepId(),
                context.gameTick(),
                position(position),
                resource));
    }

    void recordIrreversibleProcessing(
            StepRunnerContext context,
            ResourceId recipeId,
            List<ProcessResource> consumedInputs,
            List<ProcessResource> producedOutputs,
            List<BlockPos3i> affectedPositions) {
        append(new IrreversibleProcessingChange(
                nextChangeId(),
                sessionId,
                context.stepId(),
                context.gameTick(),
                recipeId,
                consumedInputs,
                producedOutputs,
                affectedPositions));
    }

    List<SessionWorldChangeReference> drainInvocationReferences() {
        List<SessionWorldChangeReference> references = List.copyOf(invocationReferences);
        invocationReferences.clear();
        return references;
    }

    RollbackReport rollback() {
        Map<BlockPos3i, WorldBlockSnapshot> currentStates = new LinkedHashMap<>();
        for (BlockPos3i position : journal.modifiedPositions()) {
            if (level.hasChunk(position.x() >> 4, position.z() >> 4)) {
                currentStates.put(position, capture(position(position)));
            }
        }
        RollbackPlan plan = journal.planRollback(currentStates);
        List<BlockPos3i> restored = new ArrayList<>();
        List<RollbackWarning> warnings = new ArrayList<>(plan.warnings());
        Optional<String> guardFailure = CreateExecutionWorldGuard.mutationFailure(level);
        if (guardFailure.isPresent()) {
            for (RollbackOperation operation : plan.operations()) {
                warnings.add(warning(operation, RollbackWarningCode.RESTORE_REJECTED,
                        "FORMAL_WORLD_EXECUTION_FORBIDDEN: " + guardFailure.orElseThrow()));
            }
            return new RollbackReport(sessionId, journal.entries().size(),
                    plan.operations().size(), List.of(), warnings);
        }
        for (RollbackOperation operation : plan.operations()) {
            BlockPos blockPos = position(operation.position());
            if (!level.hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4)) {
                warnings.add(warning(
                        operation,
                        RollbackWarningCode.CURRENT_STATE_UNAVAILABLE,
                        "Rollback target chunk became unloaded; no chunk was loaded"));
                continue;
            }
            if (!capture(blockPos).equals(operation.expectedCurrentState())) {
                warnings.add(warning(
                        operation,
                        RollbackWarningCode.CURRENT_STATE_CHANGED,
                        "Rollback target changed after planning; restore was skipped"));
                continue;
            }
            String failure = restore(blockPos, operation.restoreState());
            if (failure != null) {
                warnings.add(warning(
                        operation,
                        RollbackWarningCode.RESTORE_REJECTED,
                        failure));
                continue;
            }
            if (!capture(blockPos).equals(operation.restoreState())) {
                warnings.add(warning(
                        operation,
                        RollbackWarningCode.RESTORE_REJECTED,
                        "Restored block readback did not match the recorded before-state"));
                continue;
            }
            restored.add(operation.position());
        }
        return new RollbackReport(
                sessionId,
                journal.entries().size(),
                plan.operations().size(),
                restored,
                warnings);
    }

    private void append(WorldChangeJournal.Entry entry) {
        journal = journal.append(entry);
        invocationReferences.add(entry.reference());
    }

    private ResourceId nextChangeId() {
        changeSequence++;
        return new ResourceId(
                "create",
                String.format(Locale.ROOT, "v606/world_change/%04d", changeSequence));
    }

    private String restore(BlockPos position, WorldBlockSnapshot snapshot) {
        Block block = ForgeRegistries.BLOCKS.getValue(key(snapshot.blockId()));
        if (block == null || !snapshot.blockId().toString().equals(
                String.valueOf(ForgeRegistries.BLOCKS.getKey(block)))) {
            return "Recorded restore block is not registered: " + snapshot.blockId();
        }
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : snapshot.properties().entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                return "Recorded restore property is unavailable: " + entry.getKey();
            }
            Optional<?> value = property.getValue(entry.getValue());
            if (value.isEmpty()) {
                return "Recorded restore property value is invalid: "
                        + entry.getKey() + "=" + entry.getValue();
            }
            state = setProperty(state, property, (Comparable<?>) value.orElseThrow());
        }
        CompoundTag blockEntityTag = null;
        if (snapshot.blockEntityData().isPresent()) {
            JournalData data = snapshot.blockEntityData().orElseThrow();
            if (!data.schemaId().equals(BLOCK_ENTITY_SNBT_SCHEMA)) {
                return "Unsupported block-entity journal schema: " + data.schemaId();
            }
            if (!(block instanceof EntityBlock)) {
                return "Recorded restore block cannot contain block-entity data: "
                        + snapshot.blockId();
            }
            try {
                blockEntityTag = TagParser.parseTag(data.value());
            } catch (CommandSyntaxException exception) {
                return "Recorded block-entity data could not be parsed";
            }
        }
        if (!level.setBlockAndUpdate(position, state)) {
            return "World rejected conservative block restoration";
        }
        if (blockEntityTag != null) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null) {
                return "Restored block has no block entity for recorded data";
            }
            blockEntity.load(blockEntityTag);
            blockEntity.setChanged();
        }
        return null;
    }

    private static RollbackWarning warning(
            RollbackOperation operation,
            RollbackWarningCode code,
            String detail) {
        return new RollbackWarning(
                operation.changeId(),
                code,
                Optional.of(operation.position()),
                detail);
    }

    private static BlockPos3i position(BlockPos value) {
        return new BlockPos3i(value.getX(), value.getY(), value.getZ());
    }

    private static BlockPos position(BlockPos3i value) {
        return new BlockPos(value.x(), value.y(), value.z());
    }

    private static ResourceLocation key(ResourceId value) {
        return ResourceLocation.fromNamespaceAndPath(value.namespace(), value.path());
    }

    private static <T extends Comparable<T>> String propertyValue(
            BlockState state,
            Property<T> property) {
        return property.getName(state.getValue(property));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setProperty(
            BlockState state,
            Property property,
            Comparable value) {
        return state.setValue(property, value);
    }
}
