package dev.stevecreate.agent.forge1201.command;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.player.GoalCatalogEntry;
import dev.stevecreate.agent.core.player.PlayerExecutionMode;
import dev.stevecreate.agent.core.player.ProductionMode;
import dev.stevecreate.agent.forge1201.player.PlayerGoalCatalog;
import dev.stevecreate.agent.forge1201.player.PlayerWorkflowSavedData;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** Player-entry GameTests that exercise the server authority behind the terminal UI. */
@PrefixGameTestTemplate(false)
public final class PlayerWorkflowGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UUID DERIVED_ORDER_PLAYER =
            UUID.fromString("f4048d42-7b94-45b7-b7a9-6630a451e515");

    private PlayerWorkflowGameTests() {}

    /**
     * A searched target must still exist when the player presses the order button.
     *
     * <p>The empty terminal query intentionally shows only the eleven reviewed goals.
     * {@code createProject} used that display subset as an authority after it had already
     * resolved the selected target from the live registry, so blue concrete appeared in
     * search and then failed as {@code TARGET_NOT_SUPPORTED}. This crosses the real
     * player entry instead of proving only that the planner can build a derived recipe.</p>
     */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "player_workflow_derived_order", timeoutTicks = 2_000)
    public static void searchedDerivedTargetCreatesAPlayerProject(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceId target = ResourceId.parse("minecraft:blue_concrete");
        check(PlayerGoalCatalog.find(target).isEmpty(),
                "blue concrete unexpectedly entered the eleven reviewed goals");
        GoalCatalogEntry derived = SingleMachineGoalResolver.resolve(level, target)
                .orElseThrow(() -> new GameTestAssertException(
                        "live registry did not admit " + target));
        int quantity = Math.toIntExact(derived.outputPerBatch());
        check(quantity > 0, "derived recipe reported no output batch");

        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(DERIVED_ORDER_PLAYER, "SteveDerivedOrderFixture"));
        PlayerWorkflowSavedData projects = PlayerWorkflowSavedData.forLevel(level);
        projects.remove(player.getUUID());
        markWorld(level);

        var searched = PlayerWorkflowService.catalog(player, "concrete");
        check(searched.stream().anyMatch(value -> value.entry().target().equals(target)
                        && value.available()),
                "concrete search did not expose an available blue concrete target");
        PlayerWorkflowService.CreateResult result = PlayerWorkflowService.createProject(
                player, target.toString(), quantity, ProductionMode.ONCE,
                PlayerExecutionMode.DIRECT);
        check(result.success(), "searched derived order was refused: " + result.statusCode());
        check(result.project().target().equals(target)
                        && result.project().quantity() == quantity,
                "created project did not preserve the searched target and quantity");
        projects.remove(player.getUUID());

        LOGGER.info("PLAYER_DERIVED_ORDER PASS target={} quantity={} searchVisible=true "
                        + "reviewedCatalog=false createProject=OK", target, quantity);
        helper.succeed();
    }

    private static void markWorld(ServerLevel level) {
        PilotWorldMarkerSavedData data = PilotWorldMarkerSavedData.forLevel(level);
        if (data.marker().isPresent()) return;
        data.mark(new PilotWorldMarkerSavedData.Marker(
                PilotWorldMarkerSavedData.SCHEMA,
                "world:player-derived-order-gametest",
                UUID.fromString("525c4ae9-0b08-4d7f-a3e4-e7cc9ae88ff4").toString(),
                "overworld-pilot-only",
                "disposable-dedicated-server-fixture",
                "fixture:player-derived-order",
                level.getGameTime()));
    }

    /** Uses real recipes and containers; does not create orders or claim UI acceptance. */
    @GameTest(template = "bastion/mobs/empty", templateNamespace = "minecraft",
            batch = "player_workflow_create_fixture", timeoutTicks = 2_000)
    public static void createClientFixturesSeedExactLiveMaterials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        markWorld(level);
        BlockPos rejectedOrigin = new BlockPos(23840, 124, 24000);
        level.setBlockAndUpdate(rejectedOrigin, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
        String validNonce = "00000000000000000000000000000001";
        Object[][] refusals = {
                {"minecraft:gravel", 0L, validNonce, "C03_FIXTURE_QUANTITY_UNSUPPORTED"},
                {"minecraft:gravel", 65L, validNonce, "C03_FIXTURE_QUANTITY_UNSUPPORTED"},
                {"minecraft:gravel", 3L, "stale", "C03_FIXTURE_NONCE_INVALID"},
                {"minecraft:air", 1L, validNonce, "C03_FIXTURE_TARGET_NOT_SUPPORTED_BY_LIVE_CATALOG"}
        };
        for (Object[] refusal : refusals) {
            try {
                PlayerCreateClientAcceptanceFixture.prepare(level, ResourceId.parse((String) refusal[0]),
                        (Long) refusal[1], rejectedOrigin, (String) refusal[2]);
                throw new GameTestAssertException("invalid fixture request was accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().startsWith((String) refusal[3]),
                        "wrong fixture refusal: " + expected.getMessage());
            }
            check(level.getBlockState(rejectedOrigin).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK),
                    "refused fixture modified arena");
        }
        LOGGER.info("PLAYER_CREATE_FIXTURE_REFUSAL PASS invalidQuantity=true invalidNonce=true unsupportedTarget=true unchanged=true");
        String[] targets = {"minecraft:gravel", "create:dough", "minecraft:stripped_oak_log",
                "create:andesite_alloy", "create:blaze_cake_base", "create:cogwheel",
                "minecraft:blue_concrete"};
        for (int i = 0; i < targets.length; i++) {
            ResourceId target = ResourceId.parse(targets[i]);
            long quantity = i == 0 ? 3 : 1;
            String nonce = String.format("%032x", i + 1);
            BlockPos origin = new BlockPos(24000 + i * 160, 124, 24000);
            var prepared = PlayerCreateClientAcceptanceFixture.prepare(level, target, quantity, origin, nonce);
            check(level.getBlockEntity(prepared.source()) instanceof ChestBlockEntity,
                    "fixture source missing for " + target);
            ChestBlockEntity source = (ChestBlockEntity) level.getBlockEntity(prepared.source());
            Map<ResourceId, Long> actual = new LinkedHashMap<>();
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                var stack = source.getItem(slot);
                if (!stack.isEmpty()) actual.merge(ResourceId.parse(
                        ForgeRegistries.ITEMS.getKey(stack.getItem()).toString()),
                        (long) stack.getCount(), Math::addExact);
            }
            check(!actual.isEmpty() && actual.equals(prepared.requirements()),
                    "fixture bill mismatch for " + target + ": " + actual);
            check(level.getBlockEntity(prepared.salvage()) instanceof ChestBlockEntity salvage
                            && salvage.isEmpty(), "salvage must start empty for " + target);
            var marker = source.getPersistentData().getCompound(
                    PlayerCreateClientAcceptanceFixture.FIXTURE_MARKER_KEY);
            check(marker.getString("nonce").equals(nonce)
                            && marker.getString("target").equals(target.toString())
                            && marker.getLong("quantity") == quantity,
                    "fixture marker mismatch for " + target);
            check(level.isEmptyBlock(origin), "fixture prebuilt a machine for " + target);
            LOGGER.info("PLAYER_CREATE_FIXTURE PASS target={} quantity={} exactBill=true salvageEmpty=true nonceBound=true",
                    target, quantity);
        }
        helper.succeed();
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new GameTestAssertException(detail);
    }
}
