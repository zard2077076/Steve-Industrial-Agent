package dev.stevecreate.agent.forge1201.player.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.player.LayoutVariant;
import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowNetwork;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewRequestC2S;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.PreviewSnapshotS2C;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.ProjectWire;
import dev.stevecreate.agent.forge1201.player.net.PlayerWorkflowPackets.CancelPreviewC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Client interaction state only. Every preview response is recomputed by the server. */
@Mod.EventBusSubscriber(
        modid = SteveIndustrialAgentMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class PlacementController {
    private static ProjectWire project;
    private static BlockPos anchor;
    private static QuarterTurn orientation = QuarterTurn.ZERO;
    private static LayoutVariant variant = LayoutVariant.STANDARD;
    private static PreviewSnapshotS2C preview;
    private static long nextRequestTick;
    private static RequestIdentity requested;

    private PlacementController() {}

    public static void activate(ProjectWire value) {
        project = value;
        anchor = null;
        orientation = QuarterTurn.ZERO;
        variant = LayoutVariant.STANDARD;
        preview = null;
        requested = null;
        nextRequestTick = 0;
    }

    public static void accept(PreviewSnapshotS2C value) {
        if (project == null) return;
        if (!value.success()) {
            if (!"REQUEST_RATE_LIMITED".equals(value.statusCode())) preview = value;
            return;
        }
        if (!project.projectId().equals(value.projectId())) return;
        if (anchor == null || anchor.getX() != value.anchorX() || anchor.getY() != value.anchorY()
                || anchor.getZ() != value.anchorZ() || orientation != value.orientation()
                || variant != value.variant()) return;
        preview = value;
        if (value.finalized()) {
            PlayerWorkflowClient.openSurvey(value);
        }
    }

    public static boolean active() {
        return project != null;
    }

    public static PreviewSnapshotS2C preview() {
        return preview;
    }

    public static BlockPos anchor() {
        return anchor;
    }

    public static QuarterTurn orientation() {
        return orientation;
    }

    public static LayoutVariant variant() {
        return variant;
    }

    /**
     * Acceptance-only semantic equivalent of pressing the real use key over the
     * selected anchor.  It sends the same server packet as the production input event;
     * it does not select an anchor, alter a plan or call a server service directly.
     */
    public static boolean confirmForAcceptance() {
        if (project == null || anchor == null) return false;
        request(true, true);
        return true;
    }

    public static void clearLocal() {
        project = null;
        anchor = null;
        preview = null;
        requested = null;
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || project == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clearLocal();
            return;
        }
        if (minecraft.screen != null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos next = hit.getBlockPos().relative(hit.getDirection());
        if (!next.equals(anchor)) {
            anchor = next.immutable();
            preview = null;
        }
        request(false, false);
    }

    @SubscribeEvent
    public static void mouseScroll(InputEvent.MouseScrollingEvent event) {
        if (project == null || Minecraft.getInstance().screen != null) return;
        int direction = event.getScrollDelta() > 0 ? 1 : -1;
        if (Screen.hasShiftDown()) {
            LayoutVariant[] variants = LayoutVariant.values();
            variant = variants[Math.floorMod(variant.ordinal() + direction, variants.length)];
        } else {
            QuarterTurn[] turns = QuarterTurn.values();
            orientation = turns[Math.floorMod(orientation.ordinal() + direction, turns.length)];
        }
        preview = null;
        requested = null;
        request(false, true);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (project == null || Minecraft.getInstance().screen != null
                || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (event.isAttack()) {
            cancel();
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }
        if (event.isUseItem() && anchor != null) {
            request(true, true);
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void keyboard(InputEvent.Key event) {
        if (project == null || event.getAction() != InputConstants.PRESS) return;
        if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) cancel();
    }

    private static void request(boolean finalizeSelection, boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        if (project == null || anchor == null || minecraft.level == null) return;
        RequestIdentity identity = new RequestIdentity(anchor, orientation, variant, finalizeSelection);
        long tick = minecraft.level.getGameTime();
        if (!force && (identity.equals(requested) || tick < nextRequestTick)) return;
        requested = identity;
        nextRequestTick = tick + 5;
        PlayerWorkflowNetwork.requestPreview(new PreviewRequestC2S(
                project.projectId(), project.projectNonce(),
                anchor.getX(), anchor.getY(), anchor.getZ(), orientation, variant,
                finalizeSelection));
    }

    private static void cancel() {
        if (project != null) {
            PlayerWorkflowNetwork.cancelPreview(new CancelPreviewC2S(
                    project.projectId(), project.projectNonce()));
        }
        clearLocal();
    }

    private record RequestIdentity(
            BlockPos anchor,
            QuarterTurn orientation,
            LayoutVariant variant,
            boolean finalizeSelection) {}
}
