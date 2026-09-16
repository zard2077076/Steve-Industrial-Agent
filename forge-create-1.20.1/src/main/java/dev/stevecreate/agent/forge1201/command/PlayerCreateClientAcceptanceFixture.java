package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.execution.construction.VerifiedPlanMaterialSnapshot;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlan;
import dev.stevecreate.agent.core.execution.construction.VerifiedProjectMaterialPlanFactory;
import dev.stevecreate.agent.core.execution.readiness.ExecutionWorldClassification;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.Create606PhysicalItemRouteBuilder;
import dev.stevecreate.agent.forge1201.adapter.create.internal.v606.CreateV606GoalDrivenPlanner;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Prepares a bounded disposable arena for an ordinary-player Create order.
 *
 * <p>This is deliberately a fixture, not an order shortcut: it clears and floors a bounded
 * arena, derives the exact live bill through the same planner and material factory used
 * by the player service, and seeds a real source chest plus an empty salvage chest. It never
 * creates a project, sends a player packet, or calls the execution service. The next step is
 * still the real Engineer Terminal flow.</p>
 *
 * <p>C-03 is the first useful target here because its reviewed geometry has a survival water
 * wheel and no creative motor. All C-03 through C-10 survival-material mappings are now
 * reviewed; real-client completion remains a separate gate.</p>
 */
public final class PlayerCreateClientAcceptanceFixture {
    /** Source and salvage containers stay outside the reviewed C-03 footprint. */
    public static final int SOURCE_Z_OFFSET = -8;
    public static final int SALVAGE_Z_OFFSET = -10;
    /** Bounded marker copied to the client so a runner cannot reuse an old fixture. */
    public static final String FIXTURE_MARKER_KEY = "steve_agent_c03_fixture";
    private static final int MAX_SOURCE_SLOTS = 27;

    private PlayerCreateClientAcceptanceFixture() {}

    public static Prepared prepare(
            ServerLevel level,
            ResourceId target,
            long quantity,
            BlockPos origin,
            String fixtureNonce) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("PlayerCreateClientAcceptanceFixture");
        if (PilotWorldMarkerSavedData.forLevel(level).marker().isEmpty()) {
            throw new IllegalStateException(
                    "WORLD_NOT_AUTHORIZED: run /industrialagent setup mark-test-world first");
        }
        // Bound preparation independently of the command parser, then let the live
        // player resolver enforce recipe batches and execution budgets. A fixture-only
        // gravel whitelist must not hide targets the real player catalog supports.
        if (quantity < 1 || quantity > 64) {
            throw new IllegalArgumentException(
                    "C03_FIXTURE_QUANTITY_UNSUPPORTED: range=1..64 actual=" + quantity);
        }
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("C03_FIXTURE_NOT_SERVER_THREAD");
        }
        if (fixtureNonce == null || !fixtureNonce.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("C03_FIXTURE_NONCE_INVALID");
        }

        BlockPos primary = source(origin, SOURCE_Z_OFFSET);
        BlockPos salvage = source(origin, SALVAGE_Z_OFFSET);
        if (primary.equals(salvage)) {
            throw new IllegalStateException("C03_FIXTURE_CONTAINER_POSITIONS_OVERLAP");
        }
        PilotDeploymentCommand.TargetSpec targetSpec =
                PilotDeploymentCommand.TargetSpec.supported(level, target, quantity);
        if (targetSpec == null) {
            throw new IllegalArgumentException("C03_FIXTURE_TARGET_NOT_SUPPORTED_BY_LIVE_CATALOG");
        }
        prepareArena(level, origin, primary, salvage, targetSpec.physicalModuleCount());
        CreateV606GoalDrivenPlanner.PlanningResult planning =
                CreateV606GoalDrivenPlanner.plan(
                        level,
                        target,
                        quantity,
                        targetSpec.inputs(),
                        new BlockPos3i(origin.getX(), origin.getY(), origin.getZ()),
                        dev.stevecreate.agent.core.model.QuarterTurn.ZERO,
                        ResourceId.parse("steve_industrial:acceptance/c03_player_fixture"),
                        targetSpec.inputs(),
                        ExecutionWorldClassification.ISOLATED_REPOSITORY_TEST,
                        targetSpec.materialConstraints());
        if (!(planning instanceof CreateV606GoalDrivenPlanner.Ready ready)) {
            CreateV606GoalDrivenPlanner.Failure failure =
                    (CreateV606GoalDrivenPlanner.Failure) planning;
            throw new IllegalStateException(
                    "C03_FIXTURE_PLANNER_REFUSED:" + failure.code() + ":" + failure.detail());
        }

        VerifiedPlanMaterialSnapshot snapshot = VerifiedPlanMaterialSnapshot.from(
                ready.executionReadyPlan().physicalPlan());
        if (!snapshot.powerRoutePositions().isEmpty()) {
            throw new IllegalStateException(
                    "C03_FIXTURE_POWER_ROUTE_MATERIAL_MAPPING_REQUIRED:" +
                            snapshot.powerRoutePositions().size());
        }
        int routeCells = Create606PhysicalItemRouteBuilder.interiorCellCount(
                ready.executionReadyPlan().physicalPlan().routes());
        Map<ResourceId, Long> routeMaterials = routeCells == 0
                ? Map.of()
                : Map.of(Create606PhysicalItemRouteBuilder.routeBlock(), (long) routeCells);
        LinkedHashMap<ResourceId, Long> fuels = new LinkedHashMap<>();
        ready.executionMetadata().fuelReservations().forEach(value -> fuels.merge(
                value.fuel().resourceId(), value.fuel().amount(), Math::addExact));
        VerifiedProjectMaterialPlan materialPlan = new VerifiedProjectMaterialPlanFactory().create(
                new VerifiedProjectMaterialPlanFactory.Request(
                        ResourceId.parse("player_project:c03_client_fixture"),
                        target,
                        quantity,
                        ready.runtimeRecipeFingerprint(),
                        snapshot,
                        targetSpec.inputs(),
                        routeMaterials,
                        Map.of(),
                        Map.of(),
                        fuels,
                        Map.of(),
                        Set.of()));
        Map<ResourceId, Long> requirements = materialPlan.legacyRequirementTotals();
        if (requirements.size() > MAX_SOURCE_SLOTS) {
            throw new IllegalStateException(
                    "C03_FIXTURE_BILL_EXCEEDS_ONE_CHEST:" + requirements.size());
        }
        ChestBlockEntity sourceChest = placeChest(level, primary);
        ChestBlockEntity salvageChest = placeChest(level, salvage);
        seed(sourceChest, requirements);
        long preparedAt = level.getGameTime();
        mark(sourceChest, "source", target, quantity, materialPlan.planSha256(), preparedAt,
                fixtureNonce);
        mark(salvageChest, "salvage", target, quantity, materialPlan.planSha256(), preparedAt,
                fixtureNonce);
        return new Prepared(
                target,
                quantity,
                origin.immutable(),
                primary.immutable(),
                salvage.immutable(),
                requirements,
                materialPlan.planSha256(),
                ready.runtimeRecipeFingerprint(),
                ready.executionReadyPlan().physicalPlan().placements().size(),
                routeCells,
                fixtureNonce);
    }

    public static BlockPos source(BlockPos origin) {
        return source(origin, SOURCE_Z_OFFSET);
    }

    public static BlockPos salvage(BlockPos origin) {
        return source(origin, SALVAGE_Z_OFFSET);
    }

    private static BlockPos source(BlockPos origin, int zOffset) {
        return origin.offset(0, 0, zOffset);
    }

    private static void prepareArena(
            ServerLevel level,
            BlockPos origin,
            BlockPos primary,
            BlockPos salvage,
            int physicalModuleCount) {
        // The reviewed gravel goal uses two parallel Millstones. STANDARD positions each
        // additional module sixteen blocks east, so clearing only the first footprint makes
        // the live planner correctly refuse the second one as a collision. Derive the bounded
        // east edge from the same frozen layout spacing instead of weakening collision checks.
        if (physicalModuleCount < 1 || physicalModuleCount > 8) {
            throw new IllegalArgumentException(
                    "C03_FIXTURE_MODULE_COUNT_OUT_OF_BOUNDS:" + physicalModuleCount);
        }
        int minX = origin.getX() - 5;
        int maxX = origin.getX() + 5
                + (physicalModuleCount - 1) * LayoutVariant.STANDARD.fixedModuleSpacing();
        int minZ = origin.getZ() - 12;
        int maxZ = origin.getZ() + 4;
        int floorY = origin.getY() - 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setBlockAndUpdate(new BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState());
                for (int y = origin.getY(); y <= origin.getY() + 8; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Clear and recreate both containers so repeated fixture preparation is idempotent
        // inside the disposable world and cannot preserve an old reservation's items.
        level.setBlockAndUpdate(primary, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(salvage, Blocks.AIR.defaultBlockState());
    }

    private static ChestBlockEntity placeChest(ServerLevel level, BlockPos position) {
        level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(position) instanceof ChestBlockEntity chest)) {
            throw new IllegalStateException("C03_FIXTURE_CHEST_MISSING:" + position);
        }
        chest.clearContent();
        chest.setChanged();
        return chest;
    }

    private static void seed(ChestBlockEntity chest, Map<ResourceId, Long> requirements) {
        int slot = 0;
        for (Map.Entry<ResourceId, Long> row : requirements.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .toList()) {
            Item item = item(row.getKey());
            if (item == null) {
                throw new IllegalArgumentException("C03_FIXTURE_ITEM_UNREGISTERED:" + row.getKey());
            }
            long remaining = row.getValue();
            while (remaining > 0) {
                if (slot >= MAX_SOURCE_SLOTS) {
                    throw new IllegalStateException("C03_FIXTURE_CHEST_SLOT_BUDGET");
                }
                int amount = (int) Math.min(remaining, item.getMaxStackSize());
                chest.setItem(slot++, new ItemStack(item, amount));
                remaining -= amount;
            }
        }
        chest.setChanged();
    }

    private static void mark(
            ChestBlockEntity chest,
            String role,
            ResourceId target,
            long quantity,
            String planHash,
            long preparedAt,
            String fixtureNonce) {
        CompoundTag marker = new CompoundTag();
        marker.putString("role", role);
        marker.putString("target", target.toString());
        marker.putLong("quantity", quantity);
        marker.putString("planHash", planHash);
        marker.putLong("preparedAt", preparedAt);
        marker.putString("nonce", fixtureNonce);
        chest.getPersistentData().put(FIXTURE_MARKER_KEY, marker);
        chest.setChanged();
    }

    private static Item item(ResourceId resource) {
        Item value = ForgeRegistries.ITEMS.getValue(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        resource.namespace(), resource.path()));
        return value == null || value == net.minecraft.world.item.Items.AIR ? null : value;
    }

    public record Prepared(
            ResourceId target,
            long quantity,
            BlockPos origin,
            BlockPos source,
            BlockPos salvage,
            Map<ResourceId, Long> requirements,
            String materialPlanHash,
            String runtimeFingerprint,
            int placementCount,
            int routeCellCount,
            String fixtureNonce) {
        public Prepared {
            requirements = Map.copyOf(new LinkedHashMap<>(requirements));
            if (fixtureNonce == null || !fixtureNonce.matches("[a-f0-9]{32}")) {
                throw new IllegalArgumentException("fixture nonce is invalid");
            }
        }
    }
}
