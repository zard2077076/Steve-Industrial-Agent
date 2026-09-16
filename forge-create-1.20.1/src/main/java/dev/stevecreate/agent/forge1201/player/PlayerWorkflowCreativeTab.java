package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = SteveIndustrialAgentMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlayerWorkflowCreativeTab {
    private PlayerWorkflowCreativeTab() {}

    @SubscribeEvent
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(PlayerWorkflowItems.ENGINEER_TERMINAL);
        }
    }
}
