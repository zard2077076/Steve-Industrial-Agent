package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Deterministic loader-neutral query surface for concrete implementation declarations. */
public interface MachineImplementationCatalog {
    List<MachineImplementationDescriptor> implementations();

    Optional<MachineImplementationDescriptor> find(ResourceId implementationId);

    List<MachineImplementationDescriptor> implementationsForCapability(ResourceId capabilityId);

    List<MachineImplementationDescriptor> implementationsForRecipeType(ResourceId recipeType);

    List<MachineImplementationDescriptor> implementationsForAdapter(ResourceId adapterId);

    List<MachineImplementationDescriptor> implementationsForRuntimeFingerprint(
            String runtimeFingerprint);

    List<MachineImplementationDescriptor> implementationsForMod(String modId);

    List<MachineImplementationDescriptor> implementationsWithEvidence(
            Set<VerificationEvidenceKind> requiredEvidence);

    List<MachineImplementationDescriptor> implementationsWithExecutionSupport(
            ImplementationExecutionSupport support);

    String runtimeFingerprint();

    long reloadGeneration();
}
