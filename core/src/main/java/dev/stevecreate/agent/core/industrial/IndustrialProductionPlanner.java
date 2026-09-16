package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.binding.ImplementationPortRole;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkNode;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkVerifier;
import dev.stevecreate.agent.core.electrical.ElectricalNodeKind;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialDisposition;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialLine;
import dev.stevecreate.agent.core.execution.construction.ProjectMaterialPurpose;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.FluidNetworkGraph;
import dev.stevecreate.agent.core.fluid.FluidNetworkVerifier;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.GenericProcessSpec;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Fail-closed planner for deterministic item, electrical and optional-fluid multiblocks. */
public final class IndustrialProductionPlanner {
    private static final List<IndustrialActionType> EXECUTION_ORDER = List.of(
            IndustrialActionType.PLACE_BLOCK,
            IndustrialActionType.FORM_MULTIBLOCK,
            IndustrialActionType.CONNECT_RESOURCE,
            IndustrialActionType.INSERT_RESOURCE,
            IndustrialActionType.START_MACHINE,
            IndustrialActionType.OBSERVE_STATUS);

    public PlanningResult plan(Request request) {
        Objects.requireNonNull(request, "request");
        try {
            return planChecked(request);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return new Refused("INDUSTRIAL_PLAN_INVALID", failure.getMessage());
        }
    }

    private PlanningResult planChecked(Request request) {
        MultiblockStructureContract structure = request.descriptor().multiblock().orElse(null);
        if (structure == null) return refused("MULTIBLOCK_REQUIRED",
                "industrial implementation has no multiblock structure");
        if (!structure.supportedOrientations().contains(request.orientation())) {
            return refused("ORIENTATION_UNSUPPORTED", "multiblock orientation is unsupported");
        }
        if (!request.process().requiredMachineCapabilities().contains(
                request.descriptor().implementationId())) {
            return refused("CAPABILITY_MISMATCH", "process does not require the selected implementation");
        }
        Set<GenericResourceType> processTypes = new LinkedHashSet<>();
        request.process().inputs().forEach(value -> processTypes.add(value.resourceType()));
        request.process().outputs().forEach(value -> processTypes.add(value.resourceType()));
        if (!request.descriptor().energyAndProcessResources().containsAll(processTypes)) {
            return refused("RESOURCE_TYPE_UNSUPPORTED", "implementation lacks a process resource type");
        }
        long outputPerBatch = request.process().outputs().stream()
                .filter(value -> value.resourceId().equals(request.target())
                        && value.resourceType() == GenericResourceType.ITEM)
                .mapToLong(ProcessResource::amount).sum();
        if (outputPerBatch < 1) return refused("TARGET_OUTPUT_MISSING",
                "process has no deterministic item output for the target");
        long batches = Math.floorDiv(Math.addExact(request.targetQuantity(), outputPerBatch - 1),
                outputPerBatch);
        PowerPlanning power = verifyPower(request, batches);
        if (power.failureCode() != null) return refused(power.failureCode(),
                request.power().mode() == IndustrialPowerMode.ELECTRICAL_NETWORK
                        ? "electrical network cannot authorize the selected production batch"
                        : "power source cannot authorize the selected production batch");

        List<IndustrialComponentPlacement> components = resolveComponents(
                structure, request.anchor(), request.orientation());
        Set<BlockPos3i> positions = components.stream().map(IndustrialComponentPlacement::position)
                .collect(java.util.stream.Collectors.toSet());
        if (!request.buildablePositions().containsAll(positions)) {
            return refused("SITE_OBSTRUCTED_OR_UNAUTHORIZED",
                    "not every multiblock component is inside the verified buildable site");
        }

        Map<FluidIdentity, Long> fluids = fluidRequirements(request.process(), batches);
        if (!fluids.isEmpty()) {
            String fluidFailure = verifyFluids(request.fluidNetwork(), fluids);
            if (fluidFailure != null) return refused(fluidFailure,
                    "fluid network cannot authorize the selected production batch");
        }
        List<ProjectMaterialLine> materials = materials(
                request, components, batches, structure, power.requirement());
        List<IndustrialExecutionStep> steps = steps(request.descriptor().lifecycle());
        if (steps.isEmpty() || steps.stream().noneMatch(value ->
                value.actionType() == IndustrialActionType.OBSERVE_STATUS)) {
            return refused("LIFECYCLE_INCOMPLETE", "industrial lifecycle lacks observed completion");
        }
        if (request.requireBots() && steps.stream().anyMatch(value -> !value.botSupported())) {
            return refused("BOT_ACTION_UNSUPPORTED", "one or more required lifecycle actions lack Bot support");
        }
        int workers = Math.max(2, Math.min(5, request.botWorkers()));
        String canonical = canonical(request, components, materials, fluids,
                power.requirement(), steps,
                batches, workers);
        String hash = sha256(canonical);
        return new Ready(new IndustrialProductionPlan(
                ResourceId.parse("industrial:verified_" + hash.substring(0, 24)),
                request.projectId(), request.target(), request.targetQuantity(), batches,
                request.process(), request.descriptor().implementationId(),
                request.descriptor().adapterId(), request.anchor(), request.orientation(),
                components, materials, fluids, power.requirement(), steps, workers,
                request.descriptor().runtimeFingerprint(), hash));
    }

    private static PowerPlanning verifyPower(Request request, long batches) {
        IndustrialPowerRequest source = request.power();
        if (source.mode() == IndustrialPowerMode.ITEM_FUEL) {
            if (!request.descriptor().energyAndProcessResources()
                    .contains(GenericResourceType.HEAT)) {
                return PowerPlanning.refused("ITEM_FUEL_HEAT_CAPABILITY_MISSING");
            }
            if (request.descriptor().ports().stream().noneMatch(value ->
                    value.role() == ImplementationPortRole.ITEM_INPUT)) {
                return PowerPlanning.refused("ITEM_FUEL_INPUT_PORT_MISSING");
            }
            LinkedHashMap<ResourceId, Long> fuel = new LinkedHashMap<>();
            source.fuelItemsPerBatch().forEach((resource, amount) ->
                    fuel.put(resource, Math.multiplyExact(amount, batches)));
            return PowerPlanning.ready(IndustrialPowerRequirement.itemFuel(fuel,
                    Math.multiplyExact(source.minimumBurnTicksPerBatch(), batches)));
        }
        if (request.descriptor().ports().stream().noneMatch(value ->
                value.role() == ImplementationPortRole.ELECTRICAL_ENERGY_INPUT)) {
            return PowerPlanning.refused("ELECTRICAL_INPUT_PORT_MISSING");
        }
        ElectricalNetworkGraph network = source.electricalNetwork().orElseThrow();
        var report = new ElectricalNetworkVerifier().verify(network);
        if (!report.accepted()) return PowerPlanning.refused("ELECTRICAL_NETWORK_INVALID");
        long generation = network.nodes().values().stream()
                .mapToLong(ElectricalNetworkNode::generationPerTick).sum();
        long stored = network.nodes().values().stream()
                .mapToLong(ElectricalNetworkNode::storedEnergy).sum();
        if (network.nodes().values().stream().noneMatch(value ->
                value.kind() == ElectricalNodeKind.GENERATOR
                        || value.kind() == ElectricalNodeKind.STORAGE)) {
            return PowerPlanning.refused("ELECTRICAL_SOURCE_MISSING");
        }
        long requiredEnergy = Math.multiplyExact(source.energyPerBatchFe(), batches);
        long available;
        try {
            available = Math.addExact(stored, Math.multiplyExact(generation,
                    request.process().maximumWaitTicks()));
        } catch (ArithmeticException overflow) {
            available = Long.MAX_VALUE;
        }
        return available >= requiredEnergy
                ? PowerPlanning.ready(IndustrialPowerRequirement.electrical(
                        requiredEnergy, network.graphId(), network.fingerprint()))
                : PowerPlanning.refused("ELECTRICAL_ENERGY_INSUFFICIENT");
    }

    private static String verifyFluids(
            Optional<FluidNetworkGraph> network, Map<FluidIdentity, Long> requirements) {
        if (network.isEmpty()) return "FLUID_NETWORK_REQUIRED";
        if (!new FluidNetworkVerifier().verify(network.get()).accepted()) {
            return "FLUID_NETWORK_INVALID";
        }
        for (Map.Entry<FluidIdentity, Long> requirement : requirements.entrySet()) {
            long available = network.get().nodes().values().stream()
                    .filter(value -> value.contents().equals(Optional.of(requirement.getKey())))
                    .mapToLong(value -> value.amountMb()).sum();
            if (available < requirement.getValue()) return "FLUID_INSUFFICIENT";
        }
        return null;
    }

    private static List<IndustrialComponentPlacement> resolveComponents(
            MultiblockStructureContract structure, BlockPos3i anchor, QuarterTurn orientation) {
        return structure.components().stream().map(value -> {
            BlockPos3i rotated = rotate(value.relativePosition(), orientation);
            return new IndustrialComponentPlacement(value.roleId(), value.blockId(),
                    new BlockPos3i(anchor.x() + rotated.x(), anchor.y() + rotated.y(),
                            anchor.z() + rotated.z()), value.requiredBlockState(),
                    value.replaceableByTag());
        }).sorted(Comparator.comparing(IndustrialComponentPlacement::position,
                Comparator.comparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::z))).toList();
    }

    private static BlockPos3i rotate(BlockPos3i value, QuarterTurn turn) {
        return switch (turn) {
            case ZERO -> value;
            case CLOCKWISE_90 -> new BlockPos3i(-value.z(), value.y(), value.x());
            case CLOCKWISE_180 -> new BlockPos3i(-value.x(), value.y(), -value.z());
            case CLOCKWISE_270 -> new BlockPos3i(value.z(), value.y(), -value.x());
        };
    }

    private static Map<FluidIdentity, Long> fluidRequirements(
            GenericProcessSpec process, long batches) {
        LinkedHashMap<FluidIdentity, Long> result = new LinkedHashMap<>();
        process.inputs().stream().filter(value -> value.resourceType() == GenericResourceType.FLUID)
                .sorted(Comparator.comparing(value -> value.resourceId().toString()))
                .forEach(value -> result.merge(new FluidIdentity(value.resourceId(),
                        FluidIdentity.EMPTY_COMPONENT_SHA256),
                        Math.multiplyExact(value.amount(), batches), Math::addExact));
        return Map.copyOf(result);
    }

    private static List<ProjectMaterialLine> materials(
            Request request,
            List<IndustrialComponentPlacement> components,
            long batches,
            MultiblockStructureContract structure,
            IndustrialPowerRequirement power) {
        ArrayList<MaterialDraft> drafts = new ArrayList<>();
        Map<ResourceId, List<BlockPos3i>> componentPositions = components.stream().collect(
                java.util.stream.Collectors.groupingBy(IndustrialComponentPlacement::blockId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(IndustrialComponentPlacement::position,
                                java.util.stream.Collectors.toList())));
        componentPositions.forEach((resource, positions) -> drafts.add(new MaterialDraft(resource,
                positions.size(), ProjectMaterialPurpose.MACHINE_COMPONENT,
                ProjectMaterialDisposition.INSTALL, positions, "adapter-multiblock-template:" +
                        structure.definitionFingerprint())));
        request.process().inputs().stream()
                .filter(value -> value.resourceType() == GenericResourceType.ITEM)
                .forEach(value -> drafts.add(new MaterialDraft(value.resourceId(),
                        Math.multiplyExact(value.amount(), batches), ProjectMaterialPurpose.PROCESS_INPUT,
                        ProjectMaterialDisposition.CONSUME, List.of(),
                        "verified-industrial-recipe:" + request.process().recipeId())));
        power.fuelItems().forEach((resource, amount) -> drafts.add(new MaterialDraft(resource,
                amount, ProjectMaterialPurpose.FUEL, ProjectMaterialDisposition.CONSUME,
                List.of(), "verified-industrial-item-fuel:" + request.process().recipeId())));

        LinkedHashSet<ResourceId> leased = new LinkedHashSet<>(request.retainedTools());
        request.descriptor().lifecycle().actions().stream()
                .filter(value -> EXECUTION_ORDER.contains(value.actionType()))
                .filter(value -> value.actionType() != IndustrialActionType.CONNECT_RESOURCE)
                .flatMap(value -> value.requiredTool().stream()).forEach(leased::add);
        leased.stream().sorted(Comparator.comparing(ResourceId::toString))
                .forEach(value -> drafts.add(new MaterialDraft(value, 1,
                        ProjectMaterialPurpose.RETAINED_TOOL, ProjectMaterialDisposition.LEASE,
                        List.of(), "adapter-retained-industrial-tool")));

        request.descriptor().lifecycle().actions().stream()
                .filter(value -> value.actionType() == IndustrialActionType.CONNECT_RESOURCE)
                .flatMap(value -> value.requiredTool().stream()).distinct()
                .sorted(Comparator.comparing(ResourceId::toString))
                .forEach(value -> drafts.add(new MaterialDraft(value, 1,
                        ProjectMaterialPurpose.POWER_COMPONENT, ProjectMaterialDisposition.INSTALL,
                        List.of(request.anchor()), "adapter-electrical-connection-material")));

        drafts.sort(Comparator.comparing((MaterialDraft value) -> value.purpose().ordinal())
                .thenComparing(value -> value.resource().toString())
                .thenComparing(MaterialDraft::provenance));
        ArrayList<ProjectMaterialLine> result = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            MaterialDraft value = drafts.get(index);
            result.add(new ProjectMaterialLine(
                    ResourceId.parse("industrial_material:line_" + String.format("%03d", index)),
                    value.resource(), List.of(value.resource()), value.quantity(), value.purpose(),
                    value.disposition(), value.positions(), value.provenance()));
        }
        return List.copyOf(result);
    }

    private static List<IndustrialExecutionStep> steps(IndustrialLifecycleContract lifecycle) {
        ArrayList<IndustrialExecutionStep> result = new ArrayList<>();
        ResourceId predecessor = null;
        int ordinal = 0;
        for (IndustrialActionType type : EXECUTION_ORDER) {
            for (IndustrialActionContract action : lifecycle.actions().stream()
                    .filter(value -> value.actionType() == type)
                    .sorted(Comparator.comparing(value -> value.actionId().toString())).toList()) {
                ResourceId stepId = ResourceId.parse("industrial_step:step_"
                        + String.format("%03d", ordinal++));
                result.add(new IndustrialExecutionStep(stepId, action.actionId(), type,
                        predecessor == null ? Set.of() : Set.of(predecessor),
                        action.directSupported(), action.botSupported(), action.maximumAttempts(),
                        action.verificationContract()));
                predecessor = stepId;
            }
        }
        return List.copyOf(result);
    }

    private static String canonical(
            Request request,
            List<IndustrialComponentPlacement> components,
            List<ProjectMaterialLine> materials,
            Map<FluidIdentity, Long> fluids,
            IndustrialPowerRequirement power,
            List<IndustrialExecutionStep> steps,
            long batches,
            int workers) {
        return request.projectId() + "|" + request.target() + "|" + request.targetQuantity()
                + "|batches=" + batches + "|recipe=" + request.process().recipeId()
                + "|implementation=" + request.descriptor().implementationId()
                + "|anchor=" + request.anchor() + "|orientation=" + request.orientation()
                + "|components=" + components + "|materials=" + materials + "|fluids=" + fluids
                + "|power=" + power
                + "|steps=" + steps + "|workers=" + workers
                + "|runtime=" + request.descriptor().runtimeFingerprint();
    }

    private static Refused refused(String code, String detail) {
        return new Refused(code, detail);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public sealed interface PlanningResult permits Ready, Refused {}
    public record Ready(IndustrialProductionPlan plan) implements PlanningResult {
        public Ready { Objects.requireNonNull(plan, "plan"); }
    }
    public record Refused(String code, String detail) implements PlanningResult {
        public Refused {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            if (code.isBlank() || detail.isBlank()) {
                throw new IllegalArgumentException("industrial refusal is incomplete");
            }
        }
    }

    public record Request(
            ResourceId projectId,
            ResourceId target,
            long targetQuantity,
            GenericProcessSpec process,
            IndustrialPhysicalDescriptor descriptor,
            BlockPos3i anchor,
            QuarterTurn orientation,
            Set<BlockPos3i> buildablePositions,
            Set<ResourceId> retainedTools,
            IndustrialPowerRequest power,
            Optional<FluidNetworkGraph> fluidNetwork,
            boolean requireBots,
            int botWorkers) {
        public Request {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(target, "target");
            if (targetQuantity < 1 || targetQuantity > 1_000_000_000L) {
                throw new IllegalArgumentException("industrial request quantities are invalid");
            }
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(orientation, "orientation");
            buildablePositions = Set.copyOf(Objects.requireNonNull(
                    buildablePositions, "buildablePositions"));
            retainedTools = Set.copyOf(Objects.requireNonNull(retainedTools, "retainedTools"));
            Objects.requireNonNull(power, "power");
            fluidNetwork = Objects.requireNonNull(fluidNetwork, "fluidNetwork");
            if (botWorkers < 2 || botWorkers > 5) {
                throw new IllegalArgumentException("industrial Bot fleet must contain 2 to 5 workers");
            }
        }

        /** Compatibility constructor for the original electrical-only planning callers. */
        public Request(
                ResourceId projectId,
                ResourceId target,
                long targetQuantity,
                GenericProcessSpec process,
                IndustrialPhysicalDescriptor descriptor,
                BlockPos3i anchor,
                QuarterTurn orientation,
                Set<BlockPos3i> buildablePositions,
                Set<ResourceId> retainedTools,
                long energyPerBatchFe,
                ElectricalNetworkGraph electricalNetwork,
                Optional<FluidNetworkGraph> fluidNetwork,
                boolean requireBots,
                int botWorkers) {
            this(projectId, target, targetQuantity, process, descriptor, anchor, orientation,
                    buildablePositions, retainedTools,
                    IndustrialPowerRequest.electrical(energyPerBatchFe, electricalNetwork),
                    fluidNetwork, requireBots, botWorkers);
        }

        public long energyPerBatchFe() { return power.energyPerBatchFe(); }

        public ElectricalNetworkGraph electricalNetwork() {
            return power.electricalNetwork().orElseThrow(() ->
                    new IllegalStateException("industrial request is not electrically powered"));
        }
    }

    private record PowerPlanning(
            IndustrialPowerRequirement requirement, String failureCode) {
        private static PowerPlanning ready(IndustrialPowerRequirement requirement) {
            return new PowerPlanning(Objects.requireNonNull(requirement), null);
        }

        private static PowerPlanning refused(String code) {
            return new PowerPlanning(null, Objects.requireNonNull(code));
        }
    }

    private record MaterialDraft(
            ResourceId resource,
            long quantity,
            ProjectMaterialPurpose purpose,
            ProjectMaterialDisposition disposition,
            List<BlockPos3i> positions,
            String provenance) {}
}
