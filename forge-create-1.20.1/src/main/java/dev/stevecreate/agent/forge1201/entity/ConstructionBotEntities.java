package dev.stevecreate.agent.forge1201.entity;

import dev.stevecreate.agent.forge1201.SteveIndustrialAgentMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Registers the non-spawnable visible entity used by bounded construction Bot sessions. */
public final class ConstructionBotEntities {
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SteveIndustrialAgentMod.MOD_ID);

    public static final RegistryObject<EntityType<ConstructionBotEntity>> CONSTRUCTION_BOT =
            ENTITIES.register("construction_bot", () ->
                    EntityType.Builder
                            .of(ConstructionBotEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .noSummon()
                            .build(SteveIndustrialAgentMod.MOD_ID + ":construction_bot"));

    private ConstructionBotEntities() {}

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }

    @Mod.EventBusSubscriber(
            modid = SteveIndustrialAgentMod.MOD_ID,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class AttributesRegistration {
        private AttributesRegistration() {}

        @SubscribeEvent
        public static void registerAttributes(EntityAttributeCreationEvent event) {
            event.put(CONSTRUCTION_BOT.get(), Mob.createMobAttributes()
                    .add(Attributes.MAX_HEALTH, 20.0D)
                    .add(Attributes.MOVEMENT_SPEED, ConstructionBotEntity.MOVEMENT_PER_TICK)
                    .add(Attributes.FOLLOW_RANGE, 8.0D)
                    .build());
        }
    }
}
