package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.siteprep.ObstacleObservation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.registries.ForgeRegistries;

/** One loaded-cell authoritative Forge observation with no inventory content read. */
final class ForgeSiteSurveyAdapter {
    private ForgeSiteSurveyAdapter() {}

    static ObstacleObservation observe(ServerLevel level, BlockPos position) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("site survey requires the authoritative server thread");
        }
        if (!level.hasChunkAt(position)) {
            throw new IllegalStateException("SITE_SURVEY_STALE: target chunk is not loaded");
        }
        BlockState state = level.getBlockState(position);
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) key = new ResourceLocation("minecraft", "air");
        BlockEntity blockEntity = level.getBlockEntity(position);
        boolean container = blockEntity instanceof Container;
        boolean machine = blockEntity != null && (!key.getNamespace().equals("minecraft")
                || key.getNamespace().equals("create"));
        FluidState fluid = state.getFluidState();
        boolean fluidRisk = !fluid.isEmpty();
        boolean environmental = state.is(Blocks.LAVA) || state.is(Blocks.TNT)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
        String identity = key.toString().toLowerCase(Locale.ROOT);
        boolean natural = naturalCandidate(identity);
        boolean placementUnknown = !natural;
        float hardness = state.getDestroySpeed(level, position);
        String tool = toolRequirement(state, identity);
        return new ObstacleObservation(
                new BlockPos3i(position.getX(), position.getY(), position.getZ()),
                ResourceId.parse(key.toString()), fingerprint(state), blockEntity != null,
                container, container, machine, machine, natural, placementUnknown,
                hardness, tool, key.toString(), fluidRisk, environmental,
                true, false, true, "forge1201:authoritative-loaded-block-state");
    }

    static String fingerprint(BlockState state) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String canonical = (key == null ? "minecraft:air" : key.toString()) + "\n"
                + state.getValues().entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getKey().getName()))
                .map(entry -> entry.getKey().getName() + "="
                        + value(entry.getKey(), entry.getValue()))
                .reduce("", (left, right) -> left + "\n" + right);
        return sha256(canonical);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String value(net.minecraft.world.level.block.state.properties.Property property,
            Comparable value) {
        return property.getName(value);
    }

    private static boolean naturalCandidate(String identity) {
        return identity.equals("minecraft:grass")
                || identity.equals("minecraft:short_grass")
                || identity.equals("minecraft:tall_grass")
                || identity.equals("minecraft:fern")
                || identity.equals("minecraft:large_fern")
                || identity.equals("minecraft:dandelion")
                || identity.equals("minecraft:poppy")
                || identity.equals("minecraft:snow")
                || identity.equals("minecraft:dirt")
                || identity.equals("minecraft:grass_block")
                || identity.equals("minecraft:stone")
                || identity.endsWith("_leaves")
                || identity.endsWith("_log");
    }

    private static String toolRequirement(BlockState state, String identity) {
        if (identity.endsWith("_leaves")) return "minecraft:shears";
        if (identity.endsWith("_log")) return "minecraft:iron_axe";
        if (state.requiresCorrectToolForDrops()) return "minecraft:iron_pickaxe";
        return "minecraft:hand";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
