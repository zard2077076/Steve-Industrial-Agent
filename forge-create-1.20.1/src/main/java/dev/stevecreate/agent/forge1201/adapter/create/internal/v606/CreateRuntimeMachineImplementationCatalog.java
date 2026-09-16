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
    public static final ResourceId CRUSHING_WHEEL_PAIR_IMPLEMENTATION_ID = id(
            "create:mechanical_crushing_wheel_pair");
    public static final ResourceId MILLSTONE_IMPLEMENTATION_ID = id(
            "create:mechanical_millstone");
    public static final ResourceId PRESS_IMPLEMENTATION_ID = id("create:mechanical_press");
    public static final ResourceId SAW_IMPLEMENTATION_ID = id("create:mechanical_saw_item_processor");
    public static final ResourceId FAN_WASHING_IMPLEMENTATION_ID =
            id("create:encased_fan_washing_cell");
    public static final ResourceId FAN_SMOKING_IMPLEMENTATION_ID =
            id("create:encased_fan_smoking_cell");
    public static final ResourceId FAN_HAUNTING_IMPLEMENTATION_ID =
            id("create:encased_fan_haunting_cell");
    public static final ResourceId FAN_BLASTING_IMPLEMENTATION_ID =
            id("create:encased_fan_blasting_cell");
    public static final ResourceId BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID =
            id("create:basin_mechanical_press_compacting");
    public static final ResourceId BASIN_MIXER_IMPLEMENTATION_ID =
            id("create:basin_mechanical_mixer");
    public static final ResourceId DEPLOYER_IMPLEMENTATION_ID =
            id("create:owned_depot_deployer");

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
        Optional<RuntimeMachineCapabilityDeclaration> crushing = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.CRUSHING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> cutting = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.CUTTING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> fanWashing = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.FAN_WASHING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> fanSmoking = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.FAN_SMOKING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> fanHaunting = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.FAN_HAUNTING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> fanBlasting = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.FAN_BLASTING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> compacting = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.COMPACTING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> mixing = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.MIXING_CAPABILITY_ID);
        Optional<RuntimeMachineCapabilityDeclaration> deploying = declaration(
                capabilities, CreateRuntimeMachineCapabilityCatalog.DEPLOYING_CAPABILITY_ID);
        if (milling.isEmpty() || pressing.isEmpty() || crushing.isEmpty() || cutting.isEmpty()
                || fanWashing.isEmpty() || fanSmoking.isEmpty()
                || fanHaunting.isEmpty() || fanBlasting.isEmpty()
                || compacting.isEmpty() || mixing.isEmpty() || deploying.isEmpty()) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_CAPABILITY_MISMATCH,
                    recipes.runtimeFingerprint(),
                    "required_capabilities=create:crushing,create:cutting,create:splashing,"
                            + "minecraft:smoking,create:haunting,minecraft:blasting,"
                            + "create:milling,create:pressing,create:compacting,create:mixing,"
                            + "create:deploying",
                    "Publish the exact v606 runtime capability snapshot before implementations");
        }
        if (!milling.get().physicalExecutionAvailable()
                || !pressing.get().physicalExecutionAvailable()
                || !crushing.get().physicalExecutionAvailable()
                || !cutting.get().physicalExecutionAvailable()
                || !fanWashing.get().physicalExecutionAvailable()
                || !fanSmoking.get().physicalExecutionAvailable()
                || !fanHaunting.get().physicalExecutionAvailable()
                || !fanBlasting.get().physicalExecutionAvailable()
                || !compacting.get().physicalExecutionAvailable()
                || !mixing.get().physicalExecutionAvailable()
                || !deploying.get().physicalExecutionAvailable()) {
            return failure(
                    BindingFailureCode.IMPLEMENTATION_EXECUTION_UNVERIFIED,
                    recipes.runtimeFingerprint(),
                    "physical_execution_evidence=C-03,C-04,C-05,C-06,C-07,C-08-WIP,C-09-WIP,C-10-WIP",
                    "Keep the implementation unavailable until physical acceptance evidence exists");
        }

        String fingerprint = recipes.runtimeFingerprint();
        List<MachineImplementationDescriptor> descriptors = List.of(
                descriptor(
                        CRUSHING_WHEEL_PAIR_IMPLEMENTATION_ID,
                        id("create:crushing_wheel"),
                        id("create:entity_input_crushing"),
                        "crushing_wheel_pair",
                        crushing.get(),
                        createVersion,
                        fingerprint,
                        List.of(
                                ORIENTATION_LIMITATION,
                                "C-05 supports opposed wheels, entity input and hopper/chest output only",
                                "probabilistic outputs are observed, never promoted to guaranteed output"),
                        0,
                        false,
                        true),
                descriptor(
                        SAW_IMPLEMENTATION_ID,
                        id("create:mechanical_saw"),
                        id("create:upward_item_cutting"),
                        "mechanical_saw",
                        cutting.get(),
                        createVersion,
                        fingerprint,
                        List.of(
                                ORIENTATION_LIMITATION,
                                "C-07 supports only upward-facing item processing",
                                "world block cutting, tree felling and entity interaction are forbidden"),
                        0,
                        false,
                        false),
                fanDescriptor(
                        FAN_WASHING_IMPLEMENTATION_ID,
                        id("create:fan_washing"),
                        "fan_washing",
                        fanWashing.get(),
                        createVersion,
                        fingerprint,
                        false),
                fanDescriptor(
                        FAN_SMOKING_IMPLEMENTATION_ID,
                        id("create:fan_smoking"),
                        "fan_smoking",
                        fanSmoking.get(),
                        createVersion,
                        fingerprint,
                        true),
                fanDescriptor(
                        FAN_HAUNTING_IMPLEMENTATION_ID,
                        id("create:fan_haunting"),
                        "fan_haunting",
                        fanHaunting.get(),
                        createVersion,
                        fingerprint,
                        true),
                fanDescriptor(
                        FAN_BLASTING_IMPLEMENTATION_ID,
                        id("create:fan_blasting"),
                        "fan_blasting",
                        fanBlasting.get(),
                        createVersion,
                        fingerprint,
                        true),
                descriptor(
                        BASIN_PRESS_COMPACTING_IMPLEMENTATION_ID,
                        id("create:mechanical_press"),
                        id("create:basin_compacting"),
                        "basin_compacting",
                        compacting.get(),
                        createVersion,
                        fingerprint,
                        List.of(
                                ORIENTATION_LIMITATION,
                                "C-09 is Basin + Mechanical Press and is never C-04 belt pressing",
                                "Phase I is item-only, deterministic and NONE heat; fluids and HEATED are typed unsupported"),
                        0,
                        true,
                        false),
                descriptor(
                        BASIN_MIXER_IMPLEMENTATION_ID,
                        id("create:mechanical_mixer"),
                        id("create:basin_mixing"),
                        "basin_mixing",
                        mixing.get(),
                        createVersion,
                        fingerprint,
                        List.of(
                                ORIENTATION_LIMITATION,
                                "C-08 uses counted tag/any-of-resolved ITEM inputs and exact deterministic output",
                                "NONE and ordinary-fuel HEATED are supported; fluids, SUPERHEATED, residue and unknown NBT are typed unsupported"),
                        0,
                        true,
                        false),
                deployerDescriptor(
                        DEPLOYER_IMPLEMENTATION_ID,
                        id("create:deployer"),
                        id("create:owned_depot_item_application"),
                        deploying.get(),
                        createVersion,
                        fingerprint,
                        List.of(
                                ORIENTATION_LIMITATION,
                                "C-10 supports only downward item application on the plan-owned Depot",
                                "entity/combat/arbitrary block use/container/player inventory/private storage/unknown NBT are forbidden"),
                        0),
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
                        0,
                        false,
                        false),
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
                        0,
                        false,
                        false));
        return new RuntimeMachineImplementationCatalogResult.Success(
                new RuntimeMachineImplementationCatalogSnapshot(
                        new ImmutableMachineImplementationCatalog(
                                descriptors, fingerprint, recipes.reloadGeneration()),
                        recipes.runtime(),
                        fingerprint,
                        recipes.reloadGeneration()));
    }

    private static MachineImplementationDescriptor fanDescriptor(
            ResourceId implementationId,
            ResourceId processingMode,
            String portPrefix,
            RuntimeMachineCapabilityDeclaration declaration,
            String createVersion,
            String fingerprint,
            boolean dangerousMedium) {
        return descriptor(
                implementationId,
                id("create:encased_fan"),
                processingMode,
                portPrefix,
                declaration,
                createVersion,
                fingerprint,
                List.of(
                        ORIENTATION_LIMITATION,
                        "C-06 uses one owned directional fan, exact medium and bounded containment",
                        dangerousMedium
                                ? "Bots cannot deploy or enter the medium; Hybrid must use typed Direct fallback"
                                : "Bots remain outside the processing lane at a bounded safe standoff"),
                0,
                false,
                false);
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
            int priority,
            boolean multiplexInput,
            boolean multiplexOutput) {
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
                                VerificationEvidenceKind.INPUT_CONSUMED,
                                multiplexInput),
                        itemPort(
                                id("create:" + portPrefix + "_item_output"),
                                ImplementationPortRole.ITEM_OUTPUT,
                                PortMode.OUTPUT,
                                VerificationEvidenceKind.OUTPUT_PRODUCED,
                                multiplexOutput),
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

    private static MachineImplementationDescriptor deployerDescriptor(
            ResourceId implementationId,
            ResourceId familyId,
            ResourceId processingMode,
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
                                id("create:deployer_processed_item_input"),
                                ImplementationPortRole.ITEM_INPUT,
                                PortMode.INPUT,
                                VerificationEvidenceKind.INPUT_CONSUMED),
                        itemPort(
                                id("create:deployer_held_item_input"),
                                ImplementationPortRole.ITEM_INPUT,
                                PortMode.INPUT,
                                VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE),
                        itemPort(
                                id("create:deployer_item_output"),
                                ImplementationPortRole.ITEM_OUTPUT,
                                PortMode.OUTPUT,
                                VerificationEvidenceKind.OUTPUT_PRODUCED),
                        powerPort(id(
                                "create:deployer_rotational_power_input"))),
                capability.requiredResources(),
                capability.requiredResources().stream()
                        .anyMatch(value -> value.continuous()),
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
        return itemPort(portId, role, mode, evidence, false);
    }

    private static ImplementationPortContract itemPort(
            ResourceId portId,
            ImplementationPortRole role,
            PortMode mode,
            VerificationEvidenceKind evidence,
            boolean multiplexable) {
        return new ImplementationPortContract(
                portId,
                role,
                Optional.of(GenericResourceType.ITEM),
                mode,
                1,
                OptionalLong.empty(),
                true,
                multiplexable,
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
