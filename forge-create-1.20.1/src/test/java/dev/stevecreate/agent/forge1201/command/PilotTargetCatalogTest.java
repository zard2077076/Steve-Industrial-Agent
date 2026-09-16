package dev.stevecreate.agent.forge1201.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PilotTargetCatalogTest {
    @Test
    void publishesAReadOnlyExactSiteAnchorDiscoveryCommand() {
        var root = PilotRegionCommand.command().build();
        var siteAnchor = root.getChild("site-anchor");
        assertThat(siteAnchor).isNotNull();
        var target = siteAnchor.getChild("target_resource");
        assertThat(target).isNotNull();
        var quantity = target.getChild("quantity");
        assertThat(quantity).isNotNull();
        assertThat(quantity.getChild("zero")).isNotNull();
        assertThat(quantity.getChild("clockwise_90")).isNotNull();
        assertThat(quantity.getChild("clockwise_270")).isNotNull();
    }

    @Test
    void exposesAllPhaseIvGoalsWithinTheirTimeBoundedQuantity() {
        assertPhaseIv("minecraft:sand", Map.of("minecraft:gravel", 1L));
        assertPhaseIv("create:shaft", Map.of("create:andesite_alloy", 1L), 6);
        assertPhaseIv("create:dough", Map.of("create:wheat_flour", 1L));
        assertPhaseIv("create:blaze_cake_base", Map.of(
                "minecraft:egg", 1L,
                "minecraft:sugar", 1L,
                "create:cinder_flour", 1L));
        assertPhaseIv("create:andesite_alloy", Map.of(
                "minecraft:andesite", 1L,
                "minecraft:iron_nugget", 1L));
        assertPhaseIv("create:cogwheel", Map.of(
                "create:shaft", 1L,
                "minecraft:oak_planks", 1L));
        assertPhaseIv("minecraft:cooked_beef", Map.of("minecraft:beef", 1L));
        assertPhaseIv("minecraft:blackstone", Map.of("minecraft:cobblestone", 1L));
        assertPhaseIv("minecraft:iron_ingot", Map.of("minecraft:raw_iron", 1L));

        // A second batch is inside the executor's one-step budget and is now allowed;
        // this used to be refused because the bound was whichever single quantity had
        // been verified rather than what the deadline permits.
        assertThat(PilotDeploymentCommand.TargetSpec.supported(null,
                ResourceId.parse("create:cogwheel"), 2)).isNotNull();
        // Seven batches do not fit, and the bound is what says so.
        assertThat(PilotDeploymentCommand.TargetSpec.supported(null,
                ResourceId.parse("create:cogwheel"), 7)).isNull();
        // Not in the reviewed catalog, and a null level means nothing is derived.
        assertThat(PilotDeploymentCommand.TargetSpec.supported(null,
                ResourceId.parse("minecraft:diamond_block"), 1)).isNull();
    }

    @Test
    void retainsTheTwoAcceptedPhaseThreePilotTargetsWithoutGrantingSiteAuthority() {
        var milling = PilotDeploymentCommand.TargetSpec.supported(null,
                ResourceId.parse("minecraft:gravel"), 3);
        var pressing = PilotDeploymentCommand.TargetSpec.supported(null,
                ResourceId.parse("create:iron_sheet"), 2);

        assertThat(milling.preparedSiteRequired()).isFalse();
        assertThat(milling.inputs()).containsExactlyEntriesOf(
                Map.of(ResourceId.parse("minecraft:andesite"), 3L));
        assertThat(pressing.preparedSiteRequired()).isFalse();
        assertThat(pressing.inputs()).containsExactlyEntriesOf(
                Map.of(ResourceId.parse("minecraft:iron_ingot"), 2L));
    }

    private static void assertPhaseIv(String target, Map<String, Long> expectedInputs) {
        assertPhaseIv(target, expectedInputs, 1);
    }

    private static void assertPhaseIv(
            String target, Map<String, Long> expectedInputs, long quantity) {
        var spec = PilotDeploymentCommand.TargetSpec.supported(null,
                ResourceId.parse(target), quantity);
        assertThat(spec).isNotNull();
        assertThat(spec.preparedSiteRequired()).isTrue();
        assertThat(spec.physicalModuleCount()).isEqualTo(1);
        assertThat(spec.inputs()).containsExactlyInAnyOrderEntriesOf(expectedInputs.entrySet()
                .stream().collect(java.util.stream.Collectors.toMap(
                        entry -> ResourceId.parse(entry.getKey()), Map.Entry::getValue)));
    }
}
