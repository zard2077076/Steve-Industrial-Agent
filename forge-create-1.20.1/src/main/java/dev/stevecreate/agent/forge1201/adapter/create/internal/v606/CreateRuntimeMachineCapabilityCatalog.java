package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailure;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeFailureCode;
import dev.stevecreate.agent.adapter.api.RuntimeKnowledgeStage;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogAdapter;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogResult;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityCatalogSnapshot;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilityDeclaration;
import dev.stevecreate.agent.adapter.api.RuntimeMachineCapabilitySource;
import dev.stevecreate.agent.adapter.api.RuntimeRecipeCatalogSnapshot;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressGenericExecutionPlan;
import dev.stevecreate.agent.core.plan.WaterWheelMillstoneGenericExecutionPlan;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.planning.CapabilityVersionLimits;
import dev.stevecreate.agent.core.planning.ImmutableMachineCapabilityCatalog;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Exact, read-only machine capability declarations for Forge 1.20.1/Create 6.0.6. */
public final class CreateRuntimeMachineCapabilityCatalog
        implements RuntimeMachineCapabilityCatalogAdapter {
    public static final ResourceId ADAPTER_ID = id(
            "steve_industrial:create_runtime_1_20_1_6_0_6");
    public static final ResourceId MILLING_CAPABILITY_ID = id("create:milling");
    public static final ResourceId PRESSING_CAPABILITY_ID = id("create:pressing");

    private static final String MINECRAFT_VERSION = "1.20.1";
    private static final String FORGE_LOADER = "forge";
    private static final String FORGE_VERSION_PREFIX = "47.4.";
    private static final String CREATE_VERSION_PREFIX = "6.0.6";
    private static final ResourceId MILLING_PROVEN_RECIPE = id("create:milling/cobblestone");
    private static final ResourceId PRESSING_PROVEN_RECIPE = id("create:pressing/iron_ingot");

    @Override
    public ResourceId adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public RuntimeMachineCapabilityCatalogResult snapshot(
            RuntimeRecipeCatalogSnapshot recipeCatalog) {
        if (recipeCatalog == null) {
            return failure(
                    RuntimeKnowledgeFailureCode.CAPABILITY_CATALOG_MISSING,
                    "runtime:unavailable",
                    "runtime_recipe_catalog:missing",
                    "Machine capability discovery requires a runtime recipe catalog snapshot");
        }
        if (!ADAPTER_ID.toString().equals(recipeCatalog.runtime().adapterId())) {
            return failure(
                    RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH,
                    recipeCatalog.runtimeFingerprint(),
                    "adapter:expected=" + ADAPTER_ID,
                    "Runtime recipe catalog belongs to a different adapter");
        }
        String createVersion = recipeCatalog.runtime().industrialModVersions().get("create");
        if (!MINECRAFT_VERSION.equals(recipeCatalog.runtime().minecraftVersion())
                || !FORGE_LOADER.equals(recipeCatalog.runtime().loader())
                || !recipeCatalog.runtime().loaderVersion().startsWith(FORGE_VERSION_PREFIX)
                || createVersion == null
                || !createVersion.startsWith(CREATE_VERSION_PREFIX)) {
            return failure(
                    createVersion == null
                            ? RuntimeKnowledgeFailureCode.REQUIRED_MOD_UNAVAILABLE
                            : RuntimeKnowledgeFailureCode.RUNTIME_FINGERPRINT_MISMATCH,
                    recipeCatalog.runtimeFingerprint(),
                    "minecraft:" + recipeCatalog.runtime().minecraftVersion(),
                    "forge:" + recipeCatalog.runtime().loaderVersion(),
                    "create:" + String.valueOf(createVersion),
                    "Create machine capability catalog requires Minecraft 1.20.1, Forge 47.4.x and Create 6.0.6");
        }

        String fingerprint = recipeCatalog.runtimeFingerprint();
        RuntimeMachineCapabilityDeclaration milling = declaration(
                MILLING_CAPABILITY_ID,
                WaterWheelMillstoneGenericExecutionPlan.ACTION_HANDLER_ID,
                MILLING_PROVEN_RECIPE,
                Set.of(
                        VerificationEvidenceKind.NETWORK_CONNECTED,
                        VerificationEvidenceKind.POWER_PRESENT,
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_STARTED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        VerificationEvidenceKind.NO_NEW_CRASH,
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE),
                recipeCatalog,
                fingerprint);
        RuntimeMachineCapabilityDeclaration pressing = declaration(
                PRESSING_CAPABILITY_ID,
                BeltPressGenericExecutionPlan.ACTION_HANDLER_ID,
                PRESSING_PROVEN_RECIPE,
                Set.of(
                        VerificationEvidenceKind.NETWORK_CONNECTED,
                        VerificationEvidenceKind.POWER_PRESENT,
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_STARTED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED,
                        VerificationEvidenceKind.OUTPUT_STORED,
                        VerificationEvidenceKind.NO_NEW_CRASH,
                        VerificationEvidenceKind.CUSTOM_ADAPTER_EVIDENCE),
                recipeCatalog,
                fingerprint);
        List<RuntimeMachineCapabilityDeclaration> declarations = List.of(milling, pressing);
        return new RuntimeMachineCapabilityCatalogResult.Success(
                new RuntimeMachineCapabilityCatalogSnapshot(
                        new ImmutableMachineCapabilityCatalog(declarations.stream()
                                .map(RuntimeMachineCapabilityDeclaration::capability)
                                .toList()),
                        declarations,
                        recipeCatalog.runtime(),
                        fingerprint));
    }

    private static RuntimeMachineCapabilityDeclaration declaration(
            ResourceId capabilityId,
            ResourceId actionHandlerId,
            ResourceId provenRecipeId,
            Set<VerificationEvidenceKind> evidence,
            RuntimeRecipeCatalogSnapshot recipeCatalog,
            String fingerprint) {
        MachineCapability capability = new MachineCapability(
                capabilityId,
                ADAPTER_ID,
                Set.of(capabilityId),
                Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.ITEM),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                Set.of(actionHandlerId),
                evidence,
                Set.of(
                        id("steve_industrial:no_rotational_power"),
                        id("steve_industrial:overstressed"),
                        id("steve_industrial:recipe_unavailable")),
                new CapabilityVersionLimits(
                        Optional.of("6.0.6"),
                        Optional.of("6.0.7"),
                        Set.of(fingerprint)));
        return new RuntimeMachineCapabilityDeclaration(
                capability,
                true,
                Set.of(provenRecipeId),
                recipeCatalog.runtime(),
                fingerprint,
                RuntimeMachineCapabilitySource.VERSIONED_ADAPTER);
    }

    private static RuntimeMachineCapabilityCatalogResult failure(
            RuntimeKnowledgeFailureCode code,
            String fingerprint,
            String trace,
            String detail) {
        return new RuntimeMachineCapabilityCatalogResult.Failure(new RuntimeKnowledgeFailure(
                code,
                RuntimeKnowledgeStage.CAPABILITY_DISCOVERY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ADAPTER_ID,
                fingerprint,
                List.of(trace),
                detail));
    }

    private static RuntimeMachineCapabilityCatalogResult failure(
            RuntimeKnowledgeFailureCode code,
            String fingerprint,
            String traceOne,
            String traceTwo,
            String traceThree,
            String detail) {
        return new RuntimeMachineCapabilityCatalogResult.Failure(new RuntimeKnowledgeFailure(
                code,
                RuntimeKnowledgeStage.CAPABILITY_DISCOVERY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ADAPTER_ID,
                fingerprint,
                List.of(traceOne, traceTwo, traceThree),
                detail));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
