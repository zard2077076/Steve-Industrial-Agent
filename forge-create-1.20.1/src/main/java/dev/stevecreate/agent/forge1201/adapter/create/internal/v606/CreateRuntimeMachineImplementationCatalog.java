package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityDeclaration;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogAdapter;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineImplementationCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.core.binding.BindingFailure;
import dev.stevecreate.agent.core.binding.BindingFailureCode;
import dev.stevecreate.agent.core.binding.BindingStage;
import dev.stevecreate.agent.core.binding.ImmutableMachineImplementationCatalog;
import dev.stevecreate.agent.core.binding.ImplementationDescriptorSource;
import dev.stevecreate.agent.core.binding.ImplementationExecutionSupport;
import dev.stevecreate.agent.core.binding.ImplementationPortContract;
import dev.stevecreate.agent.core.binding.ImplementationPortRole;
import dev.stevecreate.agent.core.binding.MachineImplementationDescriptor;
import dev.stevecreate.agent.core.binding.PortTemporalSemantics;
import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Exact Create 6.0.6 implementation declarations backed by accepted C-03/C-04 evidence. */
public final class CreateRuntimeMachineImplementationCatalog
        implements RuntimeMachineImplementationCatalogAdapter {
    public static final ResourceId MILLSTONE_IMPLEMENTATION_ID = id(
            "create:mechanical_millstone");
    public static final ResourceId PRESS_IMPLEMENTATION_ID = id("create:mechanical_press");

    private static final ResourceId ADAPTER_ID = CreateRuntimeMachineCapabilityCatalog.ADAPTER_ID;
    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String CREATE_VERSION_PREFIX = "6.0.6";
    private static final String ORIENTATION_LIMITATION =
            "orientation-dependent physical ports require layout";

    @Override
    public ResourceId adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public RuntimeMachineImplementationCatalogResult snapshot(
            RuntimeRecipeCatalogSnapshot recipes,
            RuntimeMachineCapabilityCatalogSnapshot capabilities,
            boolean reloadInProgress) {
        if (reloadInProgress) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_RELOAD_IN_PROGRESS,
                    recipes == null ? "runtime:unavailable" : recipes.runtimeFingerprint(),
                    "reload_state=in_progress",
                    "Wait for datapack reload completion and rebuild the snapshot");
        }
        if (recipes == null || capabilities == null) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_CATALOG_MISSING,
                    recipes == null ? "runtime:unavailable" : recipes.runtimeFingerprint(),
                    recipes == null ? "recipe_snapshot=missing" : "capability_snapshot=missing",
                    "Capture matching recipe and capability snapshots before binding");
        }
        if (!recipes.runtime().equals(capabilities.runtime())
                || !recipes.runtimeFingerprint().equals(capabilities.runtimeFingerprint())
                || recipes.reloadGeneration() < 0) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                    recipes.runtimeFingerprint(),
                    "recipe_capability_fingerprint_match=true",
                    "Rebuild capability and implementation catalogs from the current recipe snapshot");
        }
        if (!ADAPTER_ID.toString().equals(recipes.runtime().adapterId())
                || !MINECRAFT_VERSION.equals(recipes.runtime().minecraftVersion())) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                    recipes.runtimeFingerprint(),
                    "adapter_and_minecraft_match=v606",
                    "Use the implementation catalog owned by the current runtime Adapter");
        }
        String createVersion = recipes.runtime().industrialModVersions().get("create");
        if (createVersion == null) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE,
                    recipes.runtimeFingerprint(),
                    "required_mod=create",
                    "Load the supported Create runtime before requesting implementation binding");
        }
        if (!createVersion.startsWith(CREATE_VERSION_PREFIX)) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH,
                    recipes.runtimeFingerprint(),
                    "create_version_prefix=6.0.6",
                    "Use an implementation Adapter verified for the active Create version");
        }

        Optional<RuntimeMachineCapabilityDeclaration> milling = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.MILLING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> pressing = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.PRESSING_CAPABILITY_ID);
        if (milling.isEmpty() || pressing.isEmpty()) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_CAPABILITY_MISMATCH,
                    recipes.runtimeFingerprint(),
                    "required_capabilities=create:milling,create:pressing",
                    "Publish the exact v606 runtime capability snapshot before implementations");
        }
        if (!milling.get().physicalExecutionAvailable()
                || !pressing.get().physicalExecutionAvailable()) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_EXECUTION_UNVERIFIED,
                    recipes.runtimeFingerprint(),
                    "physical_execution_evidence=C-03,C-04",
                    "Keep the implementation unavailable until physical acceptance evidence exists");
        }

        String fingerprint = recipes.runtimeFingerprint();
        List<MachineImplementationDescriptor> descriptors = List.of(
                descriptor(
                        MILLSTONE_IMPLEMENTATION_ID,
                        id("create:millstone"),
                        id("create:inventory_batch_milling"),
                        "millstone",
                        milling.get(),
                        createVersion,
                        fingerprint,
                        List.of(
                                ORIENTATION_LIMITATION,
                                "C-03 proves direct millstone inventory processing, not transport routing"),
                        0),
                descriptor(
                        PRESS_IMPLEMENTATION_ID,
                        id("create:mechanical_press"),
                        id("create:belt_held_pressing"),
                        "mechanical_press",
                        pressing.get(),
                        createVersion,
                        fingerprint,
                        List.of(
                                ORIENTATION_LIMITATION,
                                "C-04 proves belt-held pressing with funnel/chest output, not general routing"),
                        0));
        return new RuntimeMachineImplementationCatalogResult.Success(
                new RuntimeMachineImplementationCatalogSnapshot(
                        new ImmutableMachineImplementationCatalog(
                                descriptors, fingerprint, recipes.reloadGeneration()),
                        recipes.runtime(),
                        fingerprint,
                        recipes.reloadGeneration()));
    }

    private static MachineImplementationDescriptor descriptor(
            ResourceId implementationId,
            ResourceId familyId,
            ResourceId processingMode,
            String portPrefix,
            RuntimeMachineCapabilityDeclaration declaration,
            String createVersion,
            String fingerprint,
            List<String> limitations,
            int priority) {
        MachineCapability capability = declaration.capability();
        return new MachineImplementationDescriptor(
                implementationId,
                ADAPTER_ID,
                Set.of(capability.capabilityId()),
                familyId,
                capability.supportedRecipeTypes(),
                capability.inputPortTypes(),
                capability.outputPortTypes(),
                List.of(
                        itemPort(
                                id("create:" + portPrefix + "_item_input"),
                                ImplementationPortRole.ITEM_INPUT,
                                PortMode.INPUT,
                                VerificationEvidenceKind.INPUT_CONSUMED),
                        itemPort(
                                id("create:" + portPrefix + "_item_output"),
                                ImplementationPortRole.ITEM_OUTPUT,
                                PortMode.OUTPUT,
                                VerificationEvidenceKind.OUTPUT_PRODUCED),
                        powerPort(id("create:" + portPrefix + "_rotational_power_input"))),
                capability.requiredResources(),
                capability.requiredResources().stream().anyMatch(value -> value.continuous()),
                processingMode,
                capability.collectibleEvidence(),
                capability.supportedDiagnostics(),
                ImplementationExecutionSupport.PHYSICALLY_VERIFIED,
                declaration.physicallyVerifiedRecipeIds(),
                true,
                MINECRAFT_VERSION,
                "create",
                createVersion,
                fingerprint,
                ImplementationDescriptorSource.VERSIONED_ADAPTER,
                limitations,
                priority);
    }

    private static ImplementationPortContract itemPort(
            ResourceId portId,
            ImplementationPortRole role,
            PortMode mode,
            VerificationEvidenceKind evidence) {
        return new ImplementationPortContract(
                portId,
                role,
                Optional.of(GenericResourceType.ITEM),
                mode,
                1,
                OptionalLong.empty(),
                true,
                false,
                PortTemporalSemantics.CONTINUOUS,
                Set.of(id("steve_industrial:direct_item")),
                Set.of(evidence));
    }

    private static ImplementationPortContract powerPort(ResourceId portId) {
        return new ImplementationPortContract(
                portId,
                ImplementationPortRole.ROTATIONAL_POWER_INPUT,
                Optional.of(GenericResourceType.ROTATIONAL_POWER),
                PortMode.INPUT,
                1,
                OptionalLong.empty(),
                true,
                false,
                PortTemporalSemantics.CONTINUOUS,
                Set.of(id("create:kinetic_network")),
                Set.of(
                        VerificationEvidenceKind.NETWORK_CONNECTED,
                        VerificationEvidenceKind.POWER_PRESENT));
    }

    private static Optional<RuntimeMachineCapabilityDeclaration> declaration(
            RuntimeMachineCapabilityCatalogSnapshot capabilities,
            ResourceId capabilityId) {
        return capabilities.declarations().stream()
                .filter(value -> value.capability().capabilityId().equals(capabilityId))
                .findFirst();
    }

    private RuntimeMachineImplementationCatalogResult failure(
            BindingFailureCode code,
            String runtimeFingerprint,
            String constraint,
            String nextStep) {
        return new RuntimeMachineImplementationCatalogResult.Failure(new BindingFailure(
                code,
                BindingStage.CATALOG_VALIDATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(ADAPTER_ID),
                runtimeFingerprint,
                constraint,
                List.of("implementation_catalog:" + code.name().toLowerCase()),
                "Create 6.0.6 implementation catalog could not be published",
                code == BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE,
                nextStep));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
