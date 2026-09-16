package dev.stevecreate.agent.forge1201.adapter.immersiveengineering.internal.v1020;

import dev.stevecreate.agent.core.model.ResourceId;

/**
 * The IE order identities, held apart from every direct IE symbol.
 *
 * <p>These are plain identifiers, but they used to live on {@link
 * ImmersiveEngineeringV1020Adapter}, whose own signatures reference real IE classes. A
 * server-tick service naming one of them loaded that adapter during its class initialization,
 * so on a profile without Immersive Engineering the server died before the service's own
 * "is IE loaded" guard could ever run. Nothing here touches an IE type, so naming an identity
 * cannot decide whether the mod is present.</p>
 */
public final class ImmersiveEngineeringOrderIdentities {
    public static final String MOD_ID = "immersiveengineering";
    public static final String SUPPORTED_VERSION = "1.20.1-10.2.0-183";
    public static final ResourceId ADAPTER_ID = ResourceId.parse(
            "steve_industrial:immersive_engineering_v1020");
    public static final ResourceId METAL_PRESS_TYPE = ResourceId.parse(
            "immersiveengineering:metal_press");
    public static final ResourceId METAL_PRESS_ORDER_TYPE = ResourceId.parse(
            "steve_industrial:ie_metal_press");
    public static final ResourceId REVIEWED_IRON_PLATE_RECIPE = ResourceId.parse(
            "immersiveengineering:metalpress/plate_iron");

    private ImmersiveEngineeringOrderIdentities() {}
}
