package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.layout.ResolvedGeometryComponent;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic complete-BOM projection from an already verified physical plan. */
public final class VerifiedProjectMaterialPlanFactory {
    public VerifiedProjectMaterialPlan create(Request request) {
        Objects.requireNonNull(request, "request");
        ArrayList<Draft> drafts = new ArrayList<>();
        machineComponents(request, drafts);
        addMapped(drafts, request.itemRouteMaterials(), ProjectMaterialPurpose.ITEM_ROUTE_COMPONENT,
                ProjectMaterialDisposition.INSTALL, request.snapshot().itemRoutePositions(),
                "verified-item-route");
        addMapped(drafts, request.powerMaterials(), ProjectMaterialPurpose.POWER_COMPONENT,
                ProjectMaterialDisposition.INSTALL, request.snapshot().powerRoutePositions(),
                "verified-power-route");
        addMapped(drafts, request.processInputs(), ProjectMaterialPurpose.PROCESS_INPUT,
                ProjectMaterialDisposition.CONSUME, List.of(), "verified-runtime-recipe-input");
        addMapped(drafts, request.processMedia(), ProjectMaterialPurpose.PROCESS_MEDIUM,
                ProjectMaterialDisposition.CONSUME, List.of(), "verified-runtime-process-medium");
        addMapped(drafts, request.fuels(), ProjectMaterialPurpose.FUEL,
                ProjectMaterialDisposition.CONSUME, List.of(), "verified-runtime-fuel");
        addMapped(drafts, request.retainedTools(), ProjectMaterialPurpose.RETAINED_TOOL,
                ProjectMaterialDisposition.LEASE, List.of(), "verified-adapter-tool-contract");
        if ((!request.snapshot().itemRoutePositions().isEmpty()
                && request.itemRouteMaterials().isEmpty())
                || (!request.snapshot().powerRoutePositions().isEmpty()
                && request.powerMaterials().isEmpty())) {
            throw new IllegalArgumentException(
                    "verified routes require explicit adapter-owned construction materials");
        }
        drafts.sort(Comparator.comparing((Draft value) -> value.purpose().ordinal())
                .thenComparing(value -> value.resource().toString())
                .thenComparing(value -> value.provenance()));
        String canonical = canonical(request, drafts);
        String hash = sha256(canonical);
        ArrayList<ProjectMaterialLine> lines = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            Draft value = drafts.get(index);
            lines.add(new ProjectMaterialLine(
                    ResourceId.parse("material:line_" + String.format("%03d", index)),
                    value.resource(), List.of(value.resource()), value.quantity(), value.purpose(),
                    value.disposition(), value.positions(), value.provenance()));
        }
        return new VerifiedProjectMaterialPlan(
                ResourceId.parse("material:verified_" + hash.substring(0, 24)),
                request.projectId(), request.target(), request.targetQuantity(),
                request.snapshot().physicalPlanId(), request.runtimeFingerprint(), hash, lines);
    }

    private static void machineComponents(Request request, List<Draft> drafts) {
        Map<ResourceId, ArrayList<BlockPos3i>> positions = new TreeMap<>(
                Comparator.comparing(ResourceId::toString));
        for (ResolvedGeometryComponent component : request.snapshot().components()) {
            if (!request.reusedComponentPositions().contains(component.position())) {
                // A few cells are blocks with no item of their own — water, fire, soul
                // fire, lava, and Create's belt. The bill names what a player hands over,
                // not what ends up in the world, because a reservation can only hold
                // exact stacks. Everything else is its own item and passes through.
                ResourceId supplied = PlacementItemBinding.itemFor(component.blockId())
                        .orElse(component.blockId());
                positions.computeIfAbsent(supplied, ignored -> new ArrayList<>())
                        .add(component.position());
            }
        }
        positions.forEach((resource, cells) -> drafts.add(new Draft(
                resource, cells.size(), ProjectMaterialPurpose.MACHINE_COMPONENT,
                ProjectMaterialDisposition.INSTALL, List.copyOf(cells),
                "verified-physical-component")));
    }

    private static void addMapped(
            List<Draft> drafts,
            Map<ResourceId, Long> materials,
            ProjectMaterialPurpose purpose,
            ProjectMaterialDisposition disposition,
            List<BlockPos3i> positions,
            String provenance) {
        materials.entrySet().stream().sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(ResourceId::toString)))
                .forEach(value -> drafts.add(new Draft(value.getKey(), value.getValue(), purpose,
                        disposition, positions, provenance)));
    }

    private static String canonical(Request request, List<Draft> drafts) {
        StringBuilder value = new StringBuilder();
        value.append("project=").append(request.projectId()).append('\n')
                .append("target=").append(request.target()).append('@')
                .append(request.targetQuantity()).append('\n')
                .append("physical=").append(request.snapshot().physicalPlanId()).append('\n')
                .append("runtime=").append(request.runtimeFingerprint()).append('\n');
        for (Draft draft : drafts) {
            value.append(draft.purpose()).append('|').append(draft.disposition()).append('|')
                    .append(draft.resource()).append('|').append(draft.quantity()).append('|')
                    .append(draft.provenance()).append('|');
            draft.positions().stream().sorted(Comparator.comparingInt(BlockPos3i::x)
                            .thenComparingInt(BlockPos3i::y).thenComparingInt(BlockPos3i::z))
                    .forEach(position -> value.append(position.x()).append(',')
                            .append(position.y()).append(',').append(position.z()).append(';'));
            value.append('\n');
        }
        return value.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Map<ResourceId, Long> copyMaterials(Map<ResourceId, Long> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > 512) throw new IllegalArgumentException(name + " exceeds its bound");
        LinkedHashMap<ResourceId, Long> copy = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(ResourceId::toString)))
                .forEach(value -> {
                    Objects.requireNonNull(value.getKey(), name + " resource");
                    if (value.getValue() == null || value.getValue() < 1
                            || value.getValue() > 1_000_000_000L) {
                        throw new IllegalArgumentException(name + " quantity is outside its bound");
                    }
                    copy.put(value.getKey(), value.getValue());
                });
        return Map.copyOf(copy);
    }

    public record Request(
            ResourceId projectId,
            ResourceId target,
            long targetQuantity,
            String runtimeFingerprint,
            VerifiedPlanMaterialSnapshot snapshot,
            Map<ResourceId, Long> processInputs,
            Map<ResourceId, Long> itemRouteMaterials,
            Map<ResourceId, Long> powerMaterials,
            Map<ResourceId, Long> processMedia,
            Map<ResourceId, Long> fuels,
            Map<ResourceId, Long> retainedTools,
            Set<BlockPos3i> reusedComponentPositions) {
        public Request {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(target, "target");
            if (targetQuantity < 1 || targetQuantity > 1_000_000L) {
                throw new IllegalArgumentException("target quantity is outside its project bound");
            }
            Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
            if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 16_384) {
                throw new IllegalArgumentException("runtime fingerprint is blank or unbounded");
            }
            Objects.requireNonNull(snapshot, "snapshot");
            processInputs = copyMaterials(processInputs, "processInputs");
            itemRouteMaterials = copyMaterials(itemRouteMaterials, "itemRouteMaterials");
            powerMaterials = copyMaterials(powerMaterials, "powerMaterials");
            processMedia = copyMaterials(processMedia, "processMedia");
            fuels = copyMaterials(fuels, "fuels");
            retainedTools = copyMaterials(retainedTools, "retainedTools");
            reusedComponentPositions = Set.copyOf(Objects.requireNonNull(
                    reusedComponentPositions, "reusedComponentPositions"));
            if (reusedComponentPositions.stream().anyMatch(position -> snapshot.components().stream()
                    .noneMatch(component -> component.position().equals(position)))) {
                throw new IllegalArgumentException("reused position is not a verified component cell");
            }
        }
    }

    private record Draft(
            ResourceId resource,
            long quantity,
            ProjectMaterialPurpose purpose,
            ProjectMaterialDisposition disposition,
            List<BlockPos3i> positions,
            String provenance) {}
}
