package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlockEntity;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import dev.stevecreate.agent.adapter.api.create.CapabilityObservationFailure;
import dev.stevecreate.agent.adapter.api.create.CapabilityObservationFailureCode;
import dev.stevecreate.agent.adapter.api.create.CapabilityObservationRequest;
import dev.stevecreate.agent.adapter.api.create.CapabilityObservationResult;
import dev.stevecreate.agent.adapter.api.create.CapabilityObservationRoles;
import dev.stevecreate.agent.adapter.api.create.CapabilityRecipeSemantics;
import dev.stevecreate.agent.adapter.api.create.CapabilityRuntimeObservation;
import dev.stevecreate.agent.adapter.api.create.CreateCapabilityId;
import dev.stevecreate.agent.adapter.api.create.HeatRequirement;
import dev.stevecreate.agent.adapter.api.create.MediumRequirement;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

/** Shared, bounded and mutation-free Create 6.0.6 observation engine. */
final class CreateV606CapabilityRuntimeObserver {
    private static final double MAX_AIRFLOW_OBSERVATION_VOLUME = 4_096.0D;

    CapabilityObservationResult observe(
            ServerLevel level,
            String currentRuntimeFingerprint,
            String currentWorldSnapshotFingerprint,
            CapabilityObservationRequest request,
            CapabilityRecipeSemantics semantics,
            Set<CreateCapabilityId> acceptedCapabilities) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(semantics, "semantics");
        acceptedCapabilities = Set.copyOf(Objects.requireNonNull(
                acceptedCapabilities, "acceptedCapabilities"));
        if (!level.getServer().isSameThread()) {
            return failure(request, CapabilityObservationFailureCode.WRONG_THREAD,
                    "thread", "Observation requires the authoritative server thread", false);
        }
        if (!acceptedCapabilities.contains(request.capability())
                || semantics.capability() != request.capability()
                || !semantics.recipeId().equals(request.recipeId())) {
            return failure(request, CapabilityObservationFailureCode.RECIPE_MISMATCH,
                    "capability", "Observer, request and recipe semantics do not identify the same capability", false);
        }
        if (!semantics.runtimeFingerprint().equals(request.runtimeFingerprint())
                || !Objects.equals(currentRuntimeFingerprint, request.runtimeFingerprint())) {
            return failure(request, CapabilityObservationFailureCode.RUNTIME_MISMATCH,
                    "runtimeFingerprint", "Runtime census or live runtime fingerprint changed", false);
        }
        if (!Objects.equals(currentWorldSnapshotFingerprint, request.worldSnapshotFingerprint())) {
            return failure(request, CapabilityObservationFailureCode.WORLD_SNAPSHOT_MISMATCH,
                    "worldSnapshotFingerprint", "Verified world snapshot changed", false);
        }
        long currentTick = level.getGameTime();
        if (currentTick < request.requestedAtTick()
                || currentTick - request.requestedAtTick() > request.maximumObservationTicks()) {
            return failure(request, CapabilityObservationFailureCode.OBSERVATION_TIMEOUT,
                    "maximumObservationTicks", "Bounded observation window expired", false);
        }

        Map<ResourceId, BlockPos> positions = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, BlockPos3i> entry : request.componentPositions().entrySet()) {
            BlockPos position = blockPos(entry.getValue());
            if (!level.hasChunkAt(position)) {
                return failure(request, CapabilityObservationFailureCode.CHUNK_NOT_LOADED,
                        entry.getKey().toString(), "Verified component chunk is not loaded", true);
            }
            positions.put(entry.getKey(), position);
        }

        Optional<? extends Recipe<?>> liveRecipe = level.getRecipeManager().byKey(
                ResourceLocation.fromNamespaceAndPath(
                        request.recipeId().namespace(), request.recipeId().path()));
        if (liveRecipe.isEmpty() || !recipeType(liveRecipe.orElseThrow()).equals(request.capability().recipeType())) {
            return failure(request, CapabilityObservationFailureCode.RECIPE_MISMATCH,
                    "recipeId", "Live RecipeManager does not contain the expected capability recipe", false);
        }

        Map<ResourceId, ResourceId> blockIds = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        positions.forEach((role, position) -> blockIds.put(role, blockId(level, position)));
        BlockPos primaryPosition = positions.get(CapabilityObservationRoles.PRIMARY_MACHINE);
        BlockEntity primary = level.getBlockEntity(primaryPosition);
        if (primary == null) {
            return failure(request, CapabilityObservationFailureCode.COMPONENT_MISSING,
                    CapabilityObservationRoles.PRIMARY_MACHINE.toString(),
                    "Primary machine block entity is missing", true);
        }

        TypeInspection inspection = inspect(
                level, request, semantics, positions, primary);
        if (inspection.failure().isPresent()) {
            return new CapabilityObservationResult.Failure(inspection.failure().orElseThrow());
        }

        Map<ResourceId, Double> speeds = new TreeMap<>(Comparator.comparing(ResourceId::toString));
        Set<ResourceId> overstressed = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (Map.Entry<ResourceId, BlockPos> entry : positions.entrySet()) {
            BlockEntity blockEntity = level.getBlockEntity(entry.getValue());
            if (blockEntity instanceof KineticBlockEntity kinetic) {
                speeds.put(entry.getKey(), (double) kinetic.getSpeed());
                if (kinetic.isOverStressed()) {
                    overstressed.add(entry.getKey());
                }
                if (kinetic.isOverStressed()) {
                    return failure(request, CapabilityObservationFailureCode.OVERSTRESSED,
                            entry.getKey().toString(), "Kinetic component is overstressed", true);
                }
                if (Math.abs(kinetic.getSpeed()) < 0.0001F || !kinetic.isSpeedRequirementFulfilled()) {
                    return failure(request, CapabilityObservationFailureCode.INSUFFICIENT_POWER,
                            entry.getKey().toString(), "Kinetic speed is zero or below the live requirement", true);
                }
            }
        }

        Map<ResourceId, Long> items = inspection.items();
        if (!deltaSatisfied(request.baselineItemCounts(), items, request.expectedConsumedItemCounts(), false)) {
            return failure(request, CapabilityObservationFailureCode.INPUT_NOT_CONSUMED,
                    "expectedConsumedItemCounts", "Expected input delta is not yet observed", true);
        }
        if (!deltaSatisfied(request.baselineItemCounts(), items, request.expectedProducedItemCounts(), true)) {
            return failure(request, CapabilityObservationFailureCode.OUTPUT_NOT_OBSERVED,
                    "expectedProducedItemCounts", "Expected output delta is not yet observed", true);
        }

        List<String> trace = List.of(
                "observer=create-v606-read-only",
                "plan=" + request.verifiedPhysicalPlanId(),
                "element=" + request.physicalElementId(),
                "assignment=" + request.assignmentId(),
                "recipe=" + request.recipeId(),
                "worldMutation=false");
        return new CapabilityObservationResult.Success(new CapabilityRuntimeObservation(
                request.sessionId(),
                request.graphId(),
                request.taskId(),
                request.assignmentId(),
                request.verifiedPhysicalPlanId(),
                request.capability(),
                request.recipeId(),
                request.runtimeFingerprint(),
                request.worldSnapshotFingerprint(),
                request.requestedAtTick(),
                request.maximumObservationTicks(),
                currentTick,
                blockIds,
                speeds,
                overstressed,
                items,
                true,
                true,
                true,
                true,
                true,
                false,
                trace));
    }

    private TypeInspection inspect(
            ServerLevel level,
            CapabilityObservationRequest request,
            CapabilityRecipeSemantics semantics,
            Map<ResourceId, BlockPos> positions,
            BlockEntity primary) {
        return switch (request.capability()) {
            case CRUSHING -> inspectCrushing(level, request, positions, primary);
            case FAN_WASHING, FAN_SMOKING, FAN_HAUNTING, FAN_BLASTING ->
                    inspectFan(level, request, semantics, positions, primary);
            case CUTTING -> inspectCutting(request, primary);
            case MIXING_PHASE_I -> inspectBasin(
                    level, request, semantics, positions, primary, MechanicalMixerBlockEntity.class);
            case COMPACTING_PHASE_I -> inspectBasin(
                    level, request, semantics, positions, primary, MechanicalPressBlockEntity.class);
            case DEPLOYING_PHASE_I -> inspectDeploying(level, request, semantics, positions, primary);
        };
    }

    private TypeInspection inspectCrushing(
            ServerLevel level,
            CapabilityObservationRequest request,
            Map<ResourceId, BlockPos> positions,
            BlockEntity primary) {
        if (!(primary instanceof CrushingWheelBlockEntity first)) {
            return wrongBlockEntity(request, CapabilityObservationRoles.PRIMARY_MACHINE,
                    "Expected Create CrushingWheelBlockEntity");
        }
        BlockPos secondPosition = positions.get(CapabilityObservationRoles.SECONDARY_MACHINE);
        BlockPos lanePosition = positions.get(CapabilityObservationRoles.ITEM_PROCESSING_LANE);
        if (secondPosition == null || lanePosition == null) {
            return missing(request, "secondary_machine/item_processing_lane",
                    "Crushing requires two wheels and their controller lane");
        }
        BlockEntity secondEntity = level.getBlockEntity(secondPosition);
        BlockEntity laneEntity = level.getBlockEntity(lanePosition);
        if (!(secondEntity instanceof CrushingWheelBlockEntity second)
                || !(laneEntity instanceof CrushingWheelControllerBlockEntity controller)) {
            return wrongBlockEntity(request, CapabilityObservationRoles.SECONDARY_MACHINE,
                    "Crushing wheel pair or controller block entity is missing");
        }
        if (Math.signum(first.getSpeed()) == Math.signum(second.getSpeed())
                && Math.abs(first.getSpeed()) > 0.0001F && Math.abs(second.getSpeed()) > 0.0001F) {
            return typedFailure(request, CapabilityObservationFailureCode.WRONG_ROTATION,
                    "crushing_wheel_pair", "Crushing wheels are not counter-rotating", true);
        }
        Map<ResourceId, Long> items = new LinkedHashMap<>();
        countHandler(items, controller.inventory);
        return success(items);
    }

    private TypeInspection inspectFan(
            ServerLevel level,
            CapabilityObservationRequest request,
            CapabilityRecipeSemantics semantics,
            Map<ResourceId, BlockPos> positions,
            BlockEntity primary) {
        if (!(primary instanceof EncasedFanBlockEntity fan)) {
            return wrongBlockEntity(request, CapabilityObservationRoles.PRIMARY_MACHINE,
                    "Expected Create EncasedFanBlockEntity");
        }
        AirCurrent current = fan.getAirCurrent();
        if (current == null || current.bounds == null || current.segments == null
                || current.segments.isEmpty() || current.maxDistance < 1.0F) {
            return typedFailure(request, CapabilityObservationFailureCode.AIRFLOW_OBSTRUCTED,
                    "airflow", "Live fan airflow is absent or fully obstructed", true);
        }
        AABB bounds = current.bounds;
        double volume = Math.max(1.0D, bounds.getXsize())
                * Math.max(1.0D, bounds.getYsize()) * Math.max(1.0D, bounds.getZsize());
        if (volume > MAX_AIRFLOW_OBSERVATION_VOLUME) {
            return typedFailure(request, CapabilityObservationFailureCode.OBSERVATION_UNAVAILABLE,
                    "airflow.bounds", "Live airflow observation exceeds the bounded volume", false);
        }
        BlockPos mediumPosition = positions.get(CapabilityObservationRoles.MEDIUM);
        ResourceId expectedMedium = mediumBlock(semantics.environment().medium());
        if (mediumPosition == null || !blockId(level, mediumPosition).equals(expectedMedium)) {
            return typedFailure(request, CapabilityObservationFailureCode.MEDIUM_MISSING,
                    "medium", "Required fan-processing medium is not present at the verified role", true);
        }
        Map<ResourceId, Long> items = new LinkedHashMap<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            countStack(items, entity.getItem());
        }
        return success(items);
    }

    private TypeInspection inspectCutting(
            CapabilityObservationRequest request,
            BlockEntity primary) {
        if (!(primary instanceof SawBlockEntity saw)) {
            return wrongBlockEntity(request, CapabilityObservationRoles.PRIMARY_MACHINE,
                    "Expected Create SawBlockEntity");
        }
        Map<ResourceId, Long> items = new LinkedHashMap<>();
        countHandler(items, saw.inventory);
        return success(items);
    }

    private TypeInspection inspectBasin(
            ServerLevel level,
            CapabilityObservationRequest request,
            CapabilityRecipeSemantics semantics,
            Map<ResourceId, BlockPos> positions,
            BlockEntity primary,
            Class<? extends KineticBlockEntity> expectedPrimary) {
        if (!expectedPrimary.isInstance(primary)) {
            return wrongBlockEntity(request, CapabilityObservationRoles.PRIMARY_MACHINE,
                    "Expected Create basin-operating machine " + expectedPrimary.getSimpleName());
        }
        BlockPos basinPosition = positions.get(CapabilityObservationRoles.BASIN);
        if (basinPosition == null || !(level.getBlockEntity(basinPosition) instanceof BasinBlockEntity basin)) {
            return wrongBlockEntity(request, CapabilityObservationRoles.BASIN,
                    "Expected Create BasinBlockEntity");
        }
        if (semantics.environment().heat() != HeatRequirement.NONE) {
            BlockPos heatPosition = positions.get(CapabilityObservationRoles.HEAT_SOURCE);
            if (heatPosition == null) {
                return typedFailure(request, CapabilityObservationFailureCode.HEAT_MISSING,
                        "heat_source", "Heated recipe has no verified heat-source role", true);
            }
            String heatLevel = BasinBlockEntity.getHeatLevelOf(level.getBlockState(heatPosition)).name();
            boolean matches = semantics.environment().heat() == HeatRequirement.HEATED
                    ? heatLevel.equals("KINDLED") || heatLevel.equals("SEETHING")
                    : heatLevel.equals("SEETHING");
            if (!matches) {
                return typedFailure(request, CapabilityObservationFailureCode.HEAT_MISSING,
                        "heat_source", "Live basin heat does not satisfy "
                                + semantics.environment().heat(), true);
            }
        }
        Map<ResourceId, Long> items = new LinkedHashMap<>();
        countHandler(items, basin.getInputInventory());
        countHandler(items, basin.getOutputInventory());
        return success(items);
    }

    private TypeInspection inspectDeploying(
            ServerLevel level,
            CapabilityObservationRequest request,
            CapabilityRecipeSemantics semantics,
            Map<ResourceId, BlockPos> positions,
            BlockEntity primary) {
        if (!(primary instanceof DeployerBlockEntity deployer)) {
            return wrongBlockEntity(request, CapabilityObservationRoles.PRIMARY_MACHINE,
                    "Expected Create DeployerBlockEntity");
        }
        Map<ResourceId, Long> items = new LinkedHashMap<>();
        deployer.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve()
                .ifPresent(handler -> countHandler(items, handler));
        BlockPos lane = positions.get(CapabilityObservationRoles.ITEM_PROCESSING_LANE);
        if (lane != null) {
            for (ItemEntity entity : level.getEntitiesOfClass(
                    ItemEntity.class, new AABB(lane).inflate(1.0D))) {
                countStack(items, entity.getItem());
            }
        }
        if (semantics.environment().heldItem().heldItem().isPresent()
                && semantics.environment().heldItem().heldItem().orElseThrow()
                        instanceof RecipeIngredient.ExactResource exact) {
            long baseline = request.baselineItemCounts().getOrDefault(exact.resourceId(), 0L);
            if (baseline < exact.amount()) {
                return typedFailure(request, CapabilityObservationFailureCode.HELD_ITEM_MISMATCH,
                        "held_item", "Assignment baseline did not contain the exact held item", false);
            }
        }
        return success(items);
    }

    private static boolean deltaSatisfied(
            Map<ResourceId, Long> baseline,
            Map<ResourceId, Long> observed,
            Map<ResourceId, Long> expected,
            boolean produced) {
        for (Map.Entry<ResourceId, Long> entry : expected.entrySet()) {
            long before = baseline.getOrDefault(entry.getKey(), 0L);
            long after = observed.getOrDefault(entry.getKey(), 0L);
            long delta = produced ? after - before : before - after;
            if (delta < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static void countHandler(Map<ResourceId, Long> items, IItemHandler handler) {
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            countStack(items, handler.getStackInSlot(slot));
        }
    }

    private static void countStack(Map<ResourceId, Long> items, ItemStack stack) {
        if (!stack.isEmpty() && stack.getCount() > 0) {
            ResourceId item = resourceId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            items.merge(item, (long) stack.getCount(), Math::addExact);
        }
    }

    private TypeInspection wrongBlockEntity(
            CapabilityObservationRequest request,
            ResourceId role,
            String detail) {
        return typedFailure(request, CapabilityObservationFailureCode.WRONG_BLOCK_ENTITY,
                role.toString(), detail, true);
    }

    private TypeInspection missing(
            CapabilityObservationRequest request,
            String field,
            String detail) {
        return typedFailure(request, CapabilityObservationFailureCode.COMPONENT_MISSING,
                field, detail, true);
    }

    private TypeInspection typedFailure(
            CapabilityObservationRequest request,
            CapabilityObservationFailureCode code,
            String field,
            String detail,
            boolean retryable) {
        return new TypeInspection(Map.of(), Optional.of(diagnostic(
                request, code, field, detail, retryable)));
    }

    private static TypeInspection success(Map<ResourceId, Long> items) {
        return new TypeInspection(Map.copyOf(items), Optional.empty());
    }

    private CapabilityObservationResult failure(
            CapabilityObservationRequest request,
            CapabilityObservationFailureCode code,
            String field,
            String detail,
            boolean retryable) {
        return new CapabilityObservationResult.Failure(diagnostic(
                request, code, field, detail, retryable));
    }

    private CapabilityObservationFailure diagnostic(
            CapabilityObservationRequest request,
            CapabilityObservationFailureCode code,
            String field,
            String detail,
            boolean retryable) {
        return new CapabilityObservationFailure(
                code,
                request.capability(),
                request.recipeId(),
                request.assignmentId(),
                field,
                detail,
                retryable,
                List.of(
                        "observer=create-v606-read-only",
                        "plan=" + request.verifiedPhysicalPlanId(),
                        "element=" + request.physicalElementId(),
                        "worldMutation=false"));
    }

    private static ResourceId mediumBlock(MediumRequirement medium) {
        return switch (medium) {
            case WATER -> ResourceId.parse("minecraft:water");
            case FIRE -> ResourceId.parse("minecraft:fire");
            case SOUL_FIRE -> ResourceId.parse("minecraft:soul_fire");
            case LAVA -> ResourceId.parse("minecraft:lava");
            case NONE, UNKNOWN -> throw new IllegalArgumentException(
                    "Fan observation requires a known processing medium");
        };
    }

    private static ResourceId recipeType(Recipe<?> recipe) {
        return resourceId(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
    }

    private static ResourceId blockId(ServerLevel level, BlockPos position) {
        return resourceId(BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()));
    }

    private static ResourceId resourceId(ResourceLocation id) {
        if (id == null) {
            throw new IllegalArgumentException("Runtime registry value has no identity");
        }
        return new ResourceId(id.getNamespace(), id.getPath());
    }

    private static BlockPos blockPos(BlockPos3i position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private record TypeInspection(
            Map<ResourceId, Long> items,
            Optional<CapabilityObservationFailure> failure) {
        private TypeInspection {
            items = Map.copyOf(Objects.requireNonNull(items, "items"));
            failure = Objects.requireNonNull(failure, "failure");
        }
    }
}
