package dev.stevecreate.agent.core.player;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LayoutVariantTest {
    @Test
    void variantsExposeDistinctHonestSpacingAndExpansionPolicies() {
        assertThat(LayoutVariant.COMPACT.fixedModuleSpacing()).isZero();
        assertThat(LayoutVariant.STANDARD.fixedModuleSpacing()).isEqualTo(16);
        assertThat(LayoutVariant.EXPANDABLE.fixedModuleSpacing()).isEqualTo(24);
        assertThat(LayoutVariant.EXPANDABLE.reservesExpansionBay()).isTrue();
        assertThat(LayoutVariant.COMPACT.reservesExpansionBay()).isFalse();
    }
}
