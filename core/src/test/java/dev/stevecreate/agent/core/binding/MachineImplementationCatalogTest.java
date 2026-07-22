package dev.stevecreate.agent.core.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MachineImplementationCatalogTest {
    @Test
    void indexesAndFiltersImplementationsInStableIdentityOrder() {
        MachineImplementationDescriptor millstone = MachineImplementationDescriptorTest.millstone();
        MachineImplementationDescriptor alternate = alternate(millstone);
        MachineImplementationCatalog catalog = new ImmutableMachineImplementationCatalog(
                List.of(alternate, millstone), "sha256:runtime", 7);

        assertThat(catalog.implementations()).extracting(MachineImplementationDescriptor::implementationId)
                .containsExactly(id("create:mechanical_millstone"), id("virtual:millstone"));
        assertThat(catalog.find(id("create:mechanical_millstone"))).contains(millstone);
        assertThat(catalog.implementationsForCapability(id("create:milling"))).hasSize(2);
        assertThat(catalog.implementationsForRecipeType(id("create:milling"))).hasSize(2);
        assertThat(catalog.implementationsForAdapter(millstone.adapterId()))
                .containsExactly(millstone);
        assertThat(catalog.implementationsForRuntimeFingerprint("sha256:runtime")).hasSize(2);
        assertThat(catalog.implementationsForMod("create")).containsExactly(millstone);
        assertThat(catalog.implementationsWithEvidence(Set.of(
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED)))
                .hasSize(2);
        assertThat(catalog.implementationsWithExecutionSupport(
                        ImplementationExecutionSupport.PHYSICALLY_VERIFIED))
                .hasSize(2);
        assertThat(catalog.runtimeFingerprint()).isEqualTo("sha256:runtime");
        assertThat(catalog.reloadGeneration()).isEqualTo(7);
    }

    @Test
    void rejectsDuplicateIdentityAndSnapshotFingerprintDrift() {
        MachineImplementationDescriptor millstone = MachineImplementationDescriptorTest.millstone();
        assertThatThrownBy(() -> new ImmutableMachineImplementationCatalog(
                List.of(millstone, millstone), "sha256:runtime", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate implementation");
        assertThatThrownBy(() -> new ImmutableMachineImplementationCatalog(
                List.of(millstone), "sha256:other", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test
    void replacementSnapshotCannotReuseTheOldRuntimeIdentity() {
        MachineImplementationDescriptor current = MachineImplementationDescriptorTest.millstone();
        MachineImplementationCatalog generationSeven = new ImmutableMachineImplementationCatalog(
                List.of(current), "sha256:runtime", 7);
        MachineImplementationDescriptor reloaded = withFingerprint(current, "sha256:reloaded");
        MachineImplementationCatalog generationEight = new ImmutableMachineImplementationCatalog(
                List.of(reloaded), "sha256:reloaded", 8);

        assertThat(generationSeven.find(current.implementationId()).orElseThrow()
                        .runtimeFingerprint())
                .isEqualTo("sha256:runtime");
        assertThat(generationEight.find(current.implementationId()).orElseThrow()
                        .runtimeFingerprint())
                .isEqualTo("sha256:reloaded");
        assertThat(generationEight.implementationsForRuntimeFingerprint("sha256:runtime"))
                .isEmpty();
    }

    private static MachineImplementationDescriptor alternate(
            MachineImplementationDescriptor base) {
        return new MachineImplementationDescriptor(
                id("virtual:millstone"),
                id("virtual:adapter"),
                base.capabilityIds(),
                id("virtual:processor"),
                base.supportedRecipeTypes(),
                base.inputResourceTypes(),
                base.outputResourceTypes(),
                base.ports(),
                base.powerInputContracts(),
                base.requiresContinuousPower(),
                id("virtual:batch"),
                base.verificationEvidence(),
                base.diagnosticEvidence(),
                base.executionSupport(),
                Set.of(id("virtual:accepted_recipe")),
                base.bindingAllowed(),
                base.minecraftVersion(),
                "virtual_industry",
                "1.0.0",
                base.runtimeFingerprint(),
                ImplementationDescriptorSource.TEST_FIXTURE,
                List.of(),
                20);
    }

    private static MachineImplementationDescriptor withFingerprint(
            MachineImplementationDescriptor base,
            String fingerprint) {
        return new MachineImplementationDescriptor(
                base.implementationId(), base.adapterId(), base.capabilityIds(),
                base.implementationFamily(), base.supportedRecipeTypes(),
                base.inputResourceTypes(), base.outputResourceTypes(), base.ports(),
                base.powerInputContracts(), base.requiresContinuousPower(),
                base.processingMode(), base.verificationEvidence(), base.diagnosticEvidence(),
                base.executionSupport(), base.physicallyVerifiedRecipeIds(),
                base.bindingAllowed(), base.minecraftVersion(), base.modId(), base.modVersion(),
                fingerprint, base.source(), base.limitations(), base.deterministicPriority());
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
