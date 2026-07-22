package dev.stevecreate.agent.core.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReleaseCompatibilityTest {
    @Test
    void acceptsOnlyTheDocumentedPublicAlphaMatrix() {
        assertThat(ReleaseCompatibility.evaluate("1.20.1", "47.4.0", "6.0.6").compatible())
                .isTrue();
        assertThat(ReleaseCompatibility.evaluate("1.20.1", "47.4.10", "6.0.6").compatible())
                .isTrue();
    }

    @Test
    void reportsEachMismatchAndMissingCreateAsIncompatible() {
        assertThat(ReleaseCompatibility.evaluate("1.20.2", "47.4.0", "6.0.6").message())
                .startsWith("Compatibility FAIL").contains("minecraft=1.20.2");
        assertThat(ReleaseCompatibility.evaluate("1.20.1", "47.3.0", "6.0.6").compatible())
                .isFalse();
        assertThat(ReleaseCompatibility.evaluate("1.20.1", "47.4.0", null).message())
                .contains("create=not-installed");
    }
}
