package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CreateV606OwnedWorkpieceSafetyTest {
    @Test
    void handlerExposesNoGenericUseEntityPlayerInventoryOrDirectRecipeMutationPath()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/stevecreate/agent/forge1201/adapter/create/internal/v606/"
                        + "Create606OwnedWorkpieceApplicationHandler.java"));

        assertThat(source)
                .doesNotContain(
                        "DeployerHandler.activate",
                        ".getPlayer(",
                        "InteractionHand",
                        "BlockHitResult",
                        ".openMenu(",
                        ".interactAt(",
                        ".interactOn(",
                        ".getInventory(",
                        ".hurt(",
                        ".teleportTo(",
                        "level.setBlockAndUpdate(workpiece, predicted)",
                        "level.setBlock(workpiece, predicted)",
                        "Thread.sleep(",
                        "D:\\PCL2")
                .contains(
                        "AllRecipeTypes.ITEM_APPLICATION",
                        "ManualApplicationRecipe",
                        "BlockState predicted = application.transformBlock(initial)",
                        "getRollableResultsExceptBlock().isEmpty()",
                        "C-10 refuses a block-entity or container workpiece",
                        "C-10 refuses every entity inside the bounded activation volume",
                        "held.hasTag()",
                        "unrelatedBoundaryUnchanged()",
                        "setDeployerHeldItem(deployer, held.copy())");
    }

    @Test
    void authorityIsAnExactAllowlistedStateTransitionNotArbitraryBlockUse() throws IOException {
        String policy = Files.readString(Path.of(
                "../core/src/main/java/dev/stevecreate/agent/core/plan/"
                        + "OwnedWorkpieceApplicationPolicy.java"));

        assertThat(policy)
                .contains(
                        "create:item_application/andesite_casing_from_log",
                        "minecraft:stripped_oak_log",
                        "create:andesite_alloy",
                        "create:andesite_casing",
                        "arbitraryPositionUseForbidden",
                        "containerOpeningForbidden",
                        "entityInteractionForbidden",
                        "playerInventoryForbidden",
                        "privateStorageForbidden",
                        "unknownNbtMutationForbidden",
                        "unknownBlockEntityMutationForbidden")
                .doesNotContain("allowAny", "arbitraryBlockUseAllowed");
    }

    @Test
    void reloadAdmitsOnlyExactPreResourceBuildPrefix() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/stevecreate/agent/forge1201/adapter/create/internal/v606/"
                        + "Create606OwnedWorkpieceApplicationHandler.java"));

        assertThat(source)
                .contains(
                        "phase != Phase.AWAIT_POWER",
                        "recovery checkpoint refuses every resource-bearing phase",
                        "journal.entries().size() != 2",
                        "List.of(plan.drivePosition(), plan.deployerPosition())",
                        "recovery journal is not the exact two-step build prefix",
                        "recovery refuses a missing or resource-bearing Deployer",
                        "recovery requires the one exact unconsumed held item",
                        "checkpoint.runtimeIdentity().equals(runtime.canonicalIdentity())",
                        "checkpoint.worldIdentity().equals(worldIdentity(level))",
                        "checkpoint.dimensionId().equals(level.dimension().location().toString())",
                        "checkpoint.resourceBufferPosition().equals(resourceBufferPosition)",
                        "BlockPos3i resourceBufferPosition = plan.resourceBufferPosition()",
                        "liveBoundary.equals(checkpoint.unrelatedBoundary())")
                .doesNotContain(
                        "phase = checkpoint.phase()",
                        "extractExactNbtFree(plan.policy().heldItem(), 1); // recover",
                        "setBlockAndUpdate(pos(plan.policy().workpiecePosition())");
    }

    @Test
    void directBackendReusesTheTypedHandlerAndCannotClaimMaterialMovement() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/stevecreate/agent/forge1201/adapter/create/internal/v606/"
                        + "CreateV606OwnedWorkpieceDirectBackend.java"));

        assertThat(source)
                .contains(
                        "implements DirectWorldExecutor.BoundedDirectWorldBackend",
                        "new OwnedWorkpieceApplicationTaskGraphFactory().create(",
                        "Create606OwnedWorkpieceApplicationHandler.begin(level, plan, runtime)",
                        "handler.cleanupMachine()",
                        "handler.verifyTerminalBoundary()",
                        "no transfer was performed",
                        "fresh-output-buffer-boundary-rescan")
                .doesNotContain(
                        ".extractExact(",
                        ".extractExactNbtFree(",
                        ".insertExact(",
                        "DeployerHandler.activate",
                        "InteractionHand",
                        "BlockHitResult",
                        ".getPlayer(",
                        ".getInventory(",
                        ".interactAt(",
                        ".interactOn(",
                        ".teleportTo(",
                        "Thread.sleep(");
    }

    @Test
    void botWorkerReusesExistingFleetEntityAndHasNoGenericInteractionAuthority()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/stevecreate/agent/forge1201/adapter/create/internal/v606/"
                        + "CreateV606OwnedWorkpieceBotWorker.java"));

        assertThat(source)
                .contains(
                        "implements BotWorker",
                        "ConstructionBotEntities.CONSTRUCTION_BOT.get().create(level)",
                        "entity().advanceToward(",
                        "physicalBackend.executeForBot(",
                        "new BotInventory(workerId, 64, Map.of(), generation, tick)",
                        "level.getServer().isSameThread()",
                        "plan.policy().contains(")
                .containsOnlyOnce("entity.moveTo(")
                .doesNotContain(
                        "new BotFleetCoordinator(",
                        ".teleportTo(",
                        ".setPos(",
                        ".getPlayer(",
                        ".getInventory(",
                        ".openMenu(",
                        ".interactAt(",
                        ".interactOn(",
                        "InteractionHand",
                        "BlockHitResult",
                        ".extractExact(",
                        ".extractExactNbtFree(",
                        ".insertExact(",
                        "level.setBlock(",
                        "level.setBlockAndUpdate(",
                        "Thread.sleep(");
    }
}
