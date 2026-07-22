package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.graph.PortMode;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CapabilityResourceRequirement;
import dev.stevecreate.agent.core.planning.MachineCapability;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Complete loader-neutral identity and logical contract of one concrete implementation. */
public record MachineImplementationDescriptor(
        ResourceId implementationId,
        ResourceId adapterId,
        Set<ResourceId> capabilityIds,
        ResourceId implementationFamily,
        Set<ResourceId> supportedRecipeTypes,
        Set<GenericResourceType> inputResourceTypes,
        Set<GenericResourceType> outputResourceTypes,
        List<ImplementationPortContract> ports,
        Set<CapabilityResourceRequirement> powerInputContracts,
        boolean requiresContinuousPower,
        ResourceId processingMode,
        Set<VerificationEvidenceKind> verificationEvidence,
        Set<ResourceId> diagnosticEvidence,
        ImplementationExecutionSupport executionSupport,
        Set<ResourceId> physicallyVerifiedRecipeIds,
        boolean bindingAllowed,
        String minecraftVersion,
        String modId,
        String modVersion,
        String runtimeFingerprint,
        ImplementationDescriptorSource source,
        List<String> limitations,
        int deterministicPriority) {
    public static final int MAX_PORTS = 64;
    public static final int MAX_LIMITATIONS = 64;
    public static final int MAX_TEXT_LENGTH = 2_048;
    public static final int MAX_PRIORITY = 1_000_000;
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(
            ResourceId::toString);

    public MachineImplementationDescriptor {
        Objects.requireNonNull(implementationId, "implementationId");
        Objects.requireNonNull(adapterId, "adapterId");
        capabilityIds = copyIds(capabilityIds, "capabilityIds", true);
        Objects.requireNonNull(implementationFamily, "implementationFamily");
        supportedRecipeTypes = copyIds(
                supportedRecipeTypes, "supportedRecipeTypes", true);
        inputResourceTypes = copyTypes(inputResourceTypes, "inputResourceTypes", true);
        outputResourceTypes = copyTypes(outputResourceTypes, "outputResourceTypes", true);
        ports = copyPorts(ports);
        powerInputContracts = copyRequirements(powerInputContracts);
        Objects.requireNonNull(processingMode, "processingMode");
        verificationEvidence = copyEvidence(verificationEvidence, true);
        diagnosticEvidence = copyIds(diagnosticEvidence, "diagnosticEvidence", false);
        Objects.requireNonNull(executionSupport, "executionSupport");
        physicallyVerifiedRecipeIds = copyIds(
                physicallyVerifiedRecipeIds, "physicallyVerifiedRecipeIds", false);
        minecraftVersion = requireText(minecraftVersion, "minecraftVersion");
        modId = requireText(modId, "modId");
        if (!MOD_ID.matcher(modId).matches()) {
            throw new IllegalArgumentException("Invalid modId: " + modId);
        }
        modVersion = requireText(modVersion, "modVersion");
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(source, "source");
        limitations = copyText(limitations, "limitations", MAX_LIMITATIONS);
        if (deterministicPriority < 0 || deterministicPriority > MAX_PRIORITY) {
            throw new IllegalArgumentException("deterministicPriority is outside its bound");
        }

        if (executionSupport == ImplementationExecutionSupport.PHYSICALLY_VERIFIED
                != !physicallyVerifiedRecipeIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Physical execution proof must match the implementation support level");
        }
        if (bindingAllowed
                && executionSupport != ImplementationExecutionSupport.PHYSICALLY_VERIFIED) {
            throw new IllegalArgumentException(
                    "A bindable implementation must have physical execution proof");
        }
        boolean continuous = powerInputContracts.stream()
                .anyMatch(CapabilityResourceRequirement::continuous);
        if (requiresContinuousPower != continuous) {
            throw new IllegalArgumentException(
                    "requiresContinuousPower must match the declared power contracts");
        }
        validateResourcePorts(ports, inputResourceTypes, outputResourceTypes);
        validatePowerPorts(ports, powerInputContracts);
    }

    public boolean supportsCapability(ResourceId capabilityId) {
        return capabilityIds.contains(Objects.requireNonNull(capabilityId, "capabilityId"));
    }

    public boolean supportsRecipeType(ResourceId recipeType) {
        return supportedRecipeTypes.contains(Objects.requireNonNull(recipeType, "recipeType"));
    }

    private static void validateResourcePorts(
            List<ImplementationPortContract> ports,
            Set<GenericResourceType> inputs,
            Set<GenericResourceType> outputs) {
        Set<GenericResourceType> portInputs = EnumSet.noneOf(GenericResourceType.class);
        Set<GenericResourceType> portOutputs = EnumSet.noneOf(GenericResourceType.class);
        for (ImplementationPortContract port : ports) {
            port.resourceType().ifPresent(type -> {
                if (port.mode().acceptsInput()
                        && type != GenericResourceType.ROTATIONAL_POWER) {
                    portInputs.add(type);
                }
                if (port.mode().providesOutput()) {
                    portOutputs.add(type);
                }
            });
        }
        if (!portInputs.containsAll(inputs) || !portOutputs.containsAll(outputs)) {
            throw new IllegalArgumentException(
                    "Input/output resource declarations require matching logical ports");
        }
    }

    private static void validatePowerPorts(
            List<ImplementationPortContract> ports,
            Set<CapabilityResourceRequirement> requirements) {
        for (CapabilityResourceRequirement requirement : requirements) {
            boolean found = ports.stream().anyMatch(port -> port.required()
                    && port.resourceType().orElse(null) == requirement.resourceType()
                    && port.mode().acceptsInput()
                    && port.minimumAmount() >= requirement.minimumAmount()
                    && (!requirement.continuous()
                    || port.temporalSemantics() != PortTemporalSemantics.PULSED));
            if (!found) {
                throw new IllegalArgumentException(
                        "Every power contract requires a compatible required logical power port");
            }
        }
    }

    private static List<ImplementationPortContract> copyPorts(
            List<ImplementationPortContract> values) {
        Objects.requireNonNull(values, "ports");
        if (values.isEmpty() || values.size() > MAX_PORTS) {
            throw new IllegalArgumentException("ports count violates implementation bounds");
        }
        List<ImplementationPortContract> copy = new ArrayList<>(values.size());
        Set<ResourceId> ids = new HashSet<>();
        for (ImplementationPortContract value : values) {
            ImplementationPortContract port = Objects.requireNonNull(value, "ports element");
            if (!ids.add(port.portId())) {
                throw new IllegalArgumentException("Duplicate implementation port: " + port.portId());
            }
            copy.add(port);
        }
        copy.sort(Comparator.comparing(value -> value.portId().toString()));
        return List.copyOf(copy);
    }

    private static Set<CapabilityResourceRequirement> copyRequirements(
            Set<CapabilityResourceRequirement> values) {
        Objects.requireNonNull(values, "powerInputContracts");
        List<CapabilityResourceRequirement> sorted = new ArrayList<>(values.size());
        Set<GenericResourceType> types = EnumSet.noneOf(GenericResourceType.class);
        for (CapabilityResourceRequirement value : values) {
            CapabilityResourceRequirement requirement = Objects.requireNonNull(
                    value, "powerInputContracts element");
            if (!types.add(requirement.resourceType())) {
                throw new IllegalArgumentException(
                        "Duplicate power input resource type: " + requirement.resourceType());
            }
            sorted.add(requirement);
        }
        sorted.sort(Comparator.comparing(value -> value.resourceType().ordinal()));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<ResourceId> copyIds(
            Set<ResourceId> values,
            String name,
            boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > MachineCapability.MAX_IDS_PER_FIELD) {
            throw new IllegalArgumentException(name + " count violates implementation bounds");
        }
        TreeSet<ResourceId> sorted = new TreeSet<>(ID_ORDER);
        for (ResourceId value : values) {
            sorted.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<GenericResourceType> copyTypes(
            Set<GenericResourceType> values,
            String name,
            boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > GenericResourceType.values().length) {
            throw new IllegalArgumentException(name + " count violates implementation bounds");
        }
        EnumSet<GenericResourceType> sorted = EnumSet.noneOf(GenericResourceType.class);
        for (GenericResourceType value : values) {
            sorted.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static Set<VerificationEvidenceKind> copyEvidence(
            Set<VerificationEvidenceKind> values,
            boolean required) {
        Objects.requireNonNull(values, "verificationEvidence");
        if (required && values.isEmpty()) {
            throw new IllegalArgumentException("verificationEvidence cannot be empty");
        }
        EnumSet<VerificationEvidenceKind> sorted = EnumSet.noneOf(
                VerificationEvidenceKind.class);
        for (VerificationEvidenceKind value : values) {
            sorted.add(Objects.requireNonNull(value, "verificationEvidence element"));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static List<String> copyText(List<String> values, String name, int maximum) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            sorted.add(requireText(value, name + " element"));
        }
        return List.copyOf(sorted);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }
}
