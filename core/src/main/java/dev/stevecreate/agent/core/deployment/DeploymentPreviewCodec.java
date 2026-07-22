package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Canonical human-auditable JSON encoder with a stable field order. */
public final class DeploymentPreviewCodec {
    private DeploymentPreviewCodec() {}

    static String canonicalJson(DeploymentPreview value, boolean includeHash) {
        StringBuilder out = new StringBuilder(4_096).append('{');
        FieldWriter fields = new FieldWriter(out);
        fields.string("target", value.target().toString());
        fields.number("quantity", value.quantity());
        fields.strings("recipeIds", value.recipeIds().stream().map(ResourceId::toString).toList());
        fields.strings("implementationIds", value.implementationIds().stream().map(ResourceId::toString).toList());
        fields.field("anchor", nested -> position(nested, value.anchor()));
        fields.strings("orientations", value.orientations().stream().map(Enum::name).toList());
        fields.field("affectedBounds", nested -> {
            nested.append('{');
            FieldWriter box = new FieldWriter(nested);
            box.field("minimum", item -> position(item, value.affectedBounds().minimum()));
            box.field("maximum", item -> position(item, value.affectedBounds().maximum()));
            nested.append('}');
        });
        fields.array("plannedPlacements", value.plannedPlacements(), DeploymentPreviewCodec::placement);
        fields.array("plannedRemovals", value.plannedRemovals(), DeploymentPreviewCodec::observation);
        fields.array("plannedReplacements", value.plannedReplacements(), DeploymentPreviewCodec::replacement);
        fields.array("protectedBlocksEncountered", value.protectedBlocksEncountered(), DeploymentPreviewCodec::observation);
        fields.array("blockEntitiesEncountered", value.blockEntitiesEncountered(), DeploymentPreviewCodec::observation);
        fields.array("itemRoutes", value.itemRoutes(), DeploymentPreviewCodec::route);
        fields.array("rotationalPowerRoutes", value.rotationalPowerRoutes(), DeploymentPreviewCodec::route);
        fields.resourceMap("materialBillOfMaterials", value.materialBillOfMaterials());
        fields.resourceMap("requiredInputResources", value.requiredInputResources());
        fields.resourceMap("machineConstructionMaterials", value.machineConstructionMaterials());
        fields.resourceMap("expectedOutput", value.expectedOutput());
        fields.optionalLong("estimatedTicks", value.estimatedTicks());
        fields.number("stressDemand", value.stressDemand());
        fields.strings("powerSourceAssumptions", value.powerSourceAssumptions());
        fields.number("journalEstimate", value.journalEstimate());
        fields.string("rollbackClassification", value.rollbackClassification().name());
        fields.string("environmentClassification", value.environmentClassification().name());
        fields.string("runtimeFingerprint", value.runtimeFingerprint());
        fields.string("worldSnapshotFingerprint", value.worldSnapshotFingerprint());
        fields.strings("riskFindings", value.riskFindings());
        fields.strings("requiredApprovals", value.requiredApprovals());
        fields.strings("policyViolations", value.policyViolations());
        if (includeHash) fields.string("previewHash", value.previewHash());
        return out.append('}').toString();
    }

    private static void placement(StringBuilder out, DeploymentBlockPlacement value) {
        out.append('{');
        FieldWriter fields = new FieldWriter(out);
        fields.field("position", nested -> position(nested, value.position()));
        fields.string("blockId", value.blockId().toString());
        fields.textMap("blockState", value.blockState());
        out.append('}');
    }

    private static void observation(StringBuilder out, DeploymentBlockObservation value) {
        out.append('{');
        FieldWriter fields = new FieldWriter(out);
        fields.field("position", nested -> position(nested, value.position()));
        fields.string("blockId", value.blockId().toString());
        fields.bool("protectedBlock", value.protectedBlock());
        fields.bool("blockEntity", value.blockEntity());
        fields.bool("containerHasContents", value.containerHasContents());
        out.append('}');
    }

    private static void replacement(StringBuilder out, DeploymentBlockReplacement value) {
        out.append('{');
        FieldWriter fields = new FieldWriter(out);
        fields.field("position", nested -> position(nested, value.position()));
        fields.string("existingBlockId", value.existingBlockId().toString());
        fields.string("replacementBlockId", value.replacementBlockId().toString());
        out.append('}');
    }

    private static void route(StringBuilder out, DeploymentRoutePreview value) {
        out.append('{');
        FieldWriter fields = new FieldWriter(out);
        fields.string("id", value.id().toString());
        fields.string("resourceType", value.resourceType().name());
        fields.number("requiredAmount", value.requiredAmount());
        fields.number("capacity", value.capacity());
        fields.array("positions", value.positions(), DeploymentPreviewCodec::position);
        out.append('}');
    }

    private static void position(StringBuilder out, BlockPos3i value) {
        out.append('{').append("\"x\":").append(value.x())
                .append(",\"y\":").append(value.y())
                .append(",\"z\":").append(value.z()).append('}');
    }

    private static void quoted(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        out.append('"');
    }

    private static final class FieldWriter {
        private final StringBuilder out;
        private boolean first = true;

        private FieldWriter(StringBuilder out) { this.out = out; }
        private void name(String name) {
            if (!first) out.append(',');
            first = false;
            quoted(out, name);
            out.append(':');
        }
        private void string(String name, String value) { name(name); quoted(out, value); }
        private void number(String name, long value) { name(name); out.append(value); }
        private void bool(String name, boolean value) { name(name); out.append(value); }
        private void optionalLong(String name, OptionalLong value) {
            name(name);
            if (value.isPresent()) out.append(value.getAsLong()); else out.append("null");
        }
        private void field(String name, Consumer<StringBuilder> writer) { name(name); writer.accept(out); }
        private void strings(String name, List<String> values) {
            array(name, values, DeploymentPreviewCodec::quoted);
        }
        private <T> void array(String name, List<T> values, ElementWriter<T> writer) {
            name(name);
            out.append('[');
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) out.append(',');
                writer.write(out, values.get(index));
            }
            out.append(']');
        }
        private void resourceMap(String name, Map<ResourceId, Long> values) {
            name(name);
            out.append('{');
            boolean comma = false;
            TreeMap<String, Long> sorted = new TreeMap<>();
            values.forEach((key, value) -> sorted.put(key.toString(), value));
            for (Map.Entry<String, Long> entry : sorted.entrySet()) {
                if (comma) out.append(',');
                comma = true;
                quoted(out, entry.getKey());
                out.append(':').append(entry.getValue());
            }
            out.append('}');
        }
        private void textMap(String name, Map<String, String> values) {
            name(name);
            out.append('{');
            boolean comma = false;
            for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
                if (comma) out.append(',');
                comma = true;
                quoted(out, entry.getKey());
                out.append(':');
                quoted(out, entry.getValue());
            }
            out.append('}');
        }
    }

    @FunctionalInterface
    private interface ElementWriter<T> { void write(StringBuilder out, T value); }
}
