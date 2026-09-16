package dev.stevecreate.agent.forge1201.player;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Player workflow items; no optional industrial mod class appears here. */
public final class PlayerWorkflowItems {
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SteveIndustrialAgentMod.MOD_ID);

    public static final RegistryObject<Item> ENGINEER_TERMINAL = ITEMS.register(
            "engineer_terminal",
            () -> new EngineerTerminalItem(new Item.Properties().stacksTo(1)));

    private PlayerWorkflowItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
