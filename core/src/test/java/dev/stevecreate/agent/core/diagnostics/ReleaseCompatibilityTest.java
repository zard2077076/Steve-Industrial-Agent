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

    /**
     * The versions the runtime actually reports, which is not what this test used to pass.
     *
     * <p>Create reports "6.0.6-150" and this compared for exact equality against "6.0.6",
     * so /industrialagent compatibility told every real installation it was incompatible
     * — while this test stayed green, because it supplied a string the runtime never
     * produces. Found by running the client, not by running the tests.</p>
     */
    @Test
    void acceptsTheVersionStringsTheRuntimeReallyReports() {
        assertThat(ReleaseCompatibility.evaluate("1.20.1", "47.4.10", "6.0.6-150").compatible())
                .as("Create reports its build number")
                .isTrue();
        assertThat(ReleaseCompatibility.evaluate("1.20.1", "47.4.0", "6.0.6-150").message())
                .startsWith("Compatibility PASS");
    }

    /** A build suffix is not a licence to match any version starting with those digits. */
    @Test
    void stillRefusesAVersionThatMerelyStartsWithTheSameDigits() {
        assertThat(ReleaseCompatibility.evaluate("1.20.1", "47.4.0", "6.0.60").compatible())
                .isFalse();
        assertThat(ReleaseCompatibility.evaluate("1.20.1", "47.4.0", "6.0.61-1").compatible())
                .isFalse();
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
