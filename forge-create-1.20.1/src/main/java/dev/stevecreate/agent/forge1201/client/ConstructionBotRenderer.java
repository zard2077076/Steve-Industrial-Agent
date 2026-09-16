package dev.stevecreate.agent.forge1201.client;

import dev.stevecreate.agent.forge1201.entity.ConstructionBotEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

/** Player-like renderer with visibly distinct logistics and builder/inspector profiles. */
public final class ConstructionBotRenderer extends
        MobRenderer<ConstructionBotEntity, PlayerModel<ConstructionBotEntity>> {
    private static final ResourceLocation LOGISTICS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    "minecraft", "textures/entity/player/wide/steve.png");
    private static final ResourceLocation BUILDER_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    "minecraft", "textures/entity/player/wide/alex.png");

    public ConstructionBotRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(ConstructionBotEntity entity) {
        return entity.role() == ConstructionBotEntity.Role.LOGISTICS
                ? LOGISTICS_TEXTURE : BUILDER_TEXTURE;
    }
}
