package dev.stevecreate.agent.core.binding;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CandidateLogicalGraphMapper;
import dev.stevecreate.agent.core.planning.CandidatePlan;
import dev.stevecreate.agent.core.planning.CandidatePlanGenerator;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.planning.CapabilityVersionLimits;
import dev.stevecreate.agent.core.planning.CatalogRecipe;
import dev.stevecreate.agent.core.planning.ImmutableMachineCapabilityCatalog;
import dev.stevecreate.agent.core.planning.ImmutableRecipeCatalog;
import dev.stevecreate.agent.core.planning.LogicalGraphMappingResult;
import dev.stevecreate.agent.core.planning.LogicalGraphMappingSuccess;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.planning.MachineCapabilityCatalog;
import dev.stevecreate.agent.core.planning.MaterialConstraints;
import dev.stevecreate.agent.core.planning.PlanningContext;
import dev.stevecreate.agent.core.planning.PlanningResult;
import dev.stevecreate.agent.core.planning.PlanningSuccess;
import dev.stevecreate.agent.core.planning.PlanningVerificationResult;
import dev.stevecreate.agent.core.planning.PlanningVerificationSuccess;
import dev.stevecreate.agent.core.planning.PlanningVerifier;
import dev.stevecreate.agent.core.planning.ProcessDependencyGraphBuilder;
import dev.stevecreate.agent.core.planning.ProductionGoal;
import dev.stevecreate.agent.core.planning.RecipeSource;
import dev.stevecreate.agent.core.planning.VerifiedLogicalPlan;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class BindingTestFixtures {
    static final String FINGERPRINT = "fixture=1";
    static final ResourceId ADAPTER = id("fixture:adapter");
    static final CapabilityResourceRequirement POWER = new CapabilityResourceRequirement(
            GenericResourceType.ROTATIONAL_POWER, 8, true);

    private BindingTestFixtures() {}

    static VerifiedLogicalPlan singlePlan() {
        return plan(
                goal("fixture:dust", 2, Map.of()),
                List.of(recipe("fixture:milling", "fixture:milling", "industrial:milling",
                        List.of(item("fixture:ore", 1)), List.of(item("fixture:dust", 1)))),
                List.of(capability("industrial:milling", "fixture:milling")));
    }

    static VerifiedLogicalPlan multiPlan() {
        return plan(
                goal("fixture:alloy", 2, Map.of(id("fixture:coal"), 2L)),
                List.of(
                        recipe("fixture:crushing", "fixture:crushing", "industrial:crushing",
                                List.of(item("fixture:ore", 1)),
                                List.of(item("fixture:dust", 2))),
                        recipe("fixture:smelting", "fixture:smelting", "industrial:smelting",
                                List.of(item("fixture:coal", 1), item("fixture:dust", 1)),
                                List.of(item("fixture:alloy", 1)))),
                List.of(
                        capability("industrial:crushing", "fixture:crushing"),
                        capability("industrial:smelting", "fixture:smelting")));
    }

    static MachineImplementationDescriptor descriptor(
            String implementationId,
            String capabilityId,
            String recipeType,
            int priority) {
        return descriptor(implementationId, ADAPTER, capabilityId, recipeType,
                "fixture", "1", FINGERPRINT, priority,
                ImplementationExecutionSupport.PHYSICALLY_VERIFIED, true,
                GenericResourceType.ITEM);
    }

    static MachineImplementationDescriptor descriptor(
            String implementationId,
            ResourceId adapterId,
            String capabilityId,
            String recipeType,
            String modId,
            String modVersion,
            String fingerprint,
            int priority,
            ImplementationExecutionSupport support,
            boolean bindingAllowed,
            GenericResourceType resourceType) {
        Set<ResourceId> proofs = support == ImplementationExecutionSupport.PHYSICALLY_VERIFIED
                ? Set.of(id(recipeType)) : Set.of();
        return new MachineImplementationDescriptor(
                id(implementationId), adapterId, Set.of(id(capabilityId)),
                id(implementationId + "_family"), Set.of(id(recipeType)),
                Set.of(resourceType), Set.of(resourceType),
                List.of(
                        port(implementationId + "_input", resourceType, PortMode.INPUT),
                        port(implementationId + "_output", resourceType, PortMode.OUTPUT),
                        new ImplementationPortContract(
                                id(implementationId + "_power"),
                                ImplementationPortRole.ROTATIONAL_POWER_INPUT,
                                Optional.of(GenericResourceType.ROTATIONAL_POWER),
                                PortMode.INPUT, 8, OptionalLong.empty(), true, false,
                                PortTemporalSemantics.CONTINUOUS,
                                Set.of(id("fixture:kinetic")),
                                Set.of(VerificationEvidenceKind.POWER_PRESENT))),
                Set.of(POWER), true, id(implementationId + "_mode"),
                Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(id("fixture:diagnostic")), support, proofs, bindingAllowed,
                "1.20.1", modId, modVersion, fingerprint,
                ImplementationDescriptorSource.VERSIONED_ADAPTER,
                List.of("layout required"), priority);
    }

    static ImmutableMachineImplementationCatalog catalog(
            MachineImplementationDescriptor... descriptors) {
        return new ImmutableMachineImplementationCatalog(List.of(descriptors), FINGERPRINT, 0);
    }

    static BindingConstraints constraints() {
        return BindingConstraints.forRuntime(ADAPTER, Map.of("fixture", "1"), FINGERPRINT);
    }

    private static ImplementationPortContract port(
            String id,
            GenericResourceType type,
            PortMode mode) {
        ImplementationPortRole role;
        if (type == GenericResourceType.ITEM) {
            role = mode == PortMode.INPUT
                    ? ImplementationPortRole.ITEM_INPUT : ImplementationPortRole.ITEM_OUTPUT;
        } else if (type == GenericResourceType.FLUID) {
            role = mode == PortMode.INPUT
                    ? ImplementationPortRole.FLUID_INPUT : ImplementationPortRole.FLUID_OUTPUT;
        } else {
            throw new IllegalArgumentException("Unsupported fixture port type " + type);
        }
        return new ImplementationPortContract(
                BindingTestFixtures.id(id), role, Optional.of(type), mode, 1,
                OptionalLong.empty(), true, mode == PortMode.INPUT,
                PortTemporalSemantics.CONTINUOUS,
                Set.of(BindingTestFixtures.id("fixture:direct")),
                Set.of(mode == PortMode.INPUT
                        ? VerificationEvidenceKind.INPUT_CONSUMED
                        : VerificationEvidenceKind.OUTPUT_PRODUCED));
    }

    private static VerifiedLogicalPlan plan(
            ProductionGoal goal,
            List<CatalogRecipe> recipes,
            List<MachineCapability> capabilities) {
        MachineCapabilityCatalog capabilityCatalog = new ImmutableMachineCapabilityCatalog(
                capabilities);
        PlanningContext context = new PlanningContext(
                Set.of(ADAPTER), Set.of("fixture"),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER));
        PlanningResult planned = new ProcessDependencyGraphBuilder().plan(
                goal, new ImmutableRecipeCatalog(recipes), capabilityCatalog, context);
        assertThat(planned).isInstanceOf(PlanningSuccess.class);
        CandidatePlan candidate = new CandidatePlanGenerator()
                .generate((PlanningSuccess) planned).get(0);
        LogicalGraphMappingResult mapped = new CandidateLogicalGraphMapper().map(
                candidate, capabilityCatalog, context);
        assertThat(mapped).isInstanceOf(LogicalGraphMappingSuccess.class);
        PlanningVerificationResult verified = new PlanningVerifier().verify(
                candidate, ((LogicalGraphMappingSuccess) mapped).graph(), capabilityCatalog, context);
        assertThat(verified).isInstanceOf(PlanningVerificationSuccess.class);
        return ((PlanningVerificationSuccess) verified).plan();
    }

    private static CatalogRecipe recipe(
            String recipeId,
            String recipeType,
            String capability,
            List<ProcessResource> inputs,
            List<ProcessResource> outputs) {
        return new CatalogRecipe(
                id(recipeId), id(recipeType), inputs, outputs, List.of(), Set.of(id(capability)),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(20), new RecipeSource(ADAPTER, "fixture", FINGERPRINT, true));
    }

    private static MachineCapability capability(String capabilityId, String recipeType) {
        return new MachineCapability(
                id(capabilityId), ADAPTER, Set.of(id(recipeType)), Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.ITEM), Set.of(POWER), Set.of(id("fixture:process")),
                Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(id("fixture:failure")),
                new CapabilityVersionLimits(Optional.of("1"), Optional.empty(), Set.of()));
    }

    private static ProductionGoal goal(String target, long amount, Map<ResourceId, Long> owned) {
        return new ProductionGoal(
                id(target), GenericResourceType.ITEM, amount, Set.of(), Set.of(), Optional.empty(),
                MaterialConstraints.none(), List.of(), owned);
    }

    private static ProcessResource item(String id, long amount) {
        return new ProcessResource(BindingTestFixtures.id(id), GenericResourceType.ITEM, amount);
    }

    static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
