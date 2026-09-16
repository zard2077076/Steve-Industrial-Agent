package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact scope ledger for the reviewed Create C-01 through C-10 capabilities. */
public enum CreateCapabilityContractV1 {
    C01("C-01", Kind.READ_ONLY_DISCOVERY, "create:registry_scan"),
    C02("C-02", Kind.READ_ONLY_DISCOVERY, "create:kinetic_observation"),
    C03("C-03", Kind.MATERIAL_CONSTRUCTION, "create:milling"),
    C04("C-04", Kind.MATERIAL_CONSTRUCTION, "create:pressing"),
    C05("C-05", Kind.MATERIAL_CONSTRUCTION, "create:crushing"),
    C06("C-06", Kind.MATERIAL_CONSTRUCTION, "create:fan_processing"),
    C07("C-07", Kind.MATERIAL_CONSTRUCTION, "create:cutting"),
    C08("C-08", Kind.MATERIAL_CONSTRUCTION, "create:mixing"),
    C09("C-09", Kind.MATERIAL_CONSTRUCTION, "create:compacting"),
    C10("C-10", Kind.MATERIAL_CONSTRUCTION, "create:deploying");

    private final String capabilityId;
    private final Kind kind;
    private final ResourceId processCapability;

    CreateCapabilityContractV1(String capabilityId, Kind kind, String processCapability) {
        this.capabilityId = capabilityId;
        this.kind = kind;
        this.processCapability = ResourceId.parse(processCapability);
    }

    public String capabilityId() { return capabilityId; }
    public Kind kind() { return kind; }
    public ResourceId processCapability() { return processCapability; }
    public boolean materialBearing() { return kind == Kind.MATERIAL_CONSTRUCTION; }

    public static List<CreateCapabilityContractV1> ordered() { return List.of(values()); }

    public static Optional<CreateCapabilityContractV1> forProcessCapability(ResourceId capability) {
        Objects.requireNonNull(capability, "capability");
        if (capability.namespace().equals("create")
                && (capability.path().equals("splashing")
                || capability.path().equals("smoking")
                || capability.path().equals("haunting")
                || capability.path().equals("blasting"))) {
            return Optional.of(C06);
        }
        return ordered().stream().filter(value -> value.processCapability.equals(capability)).findFirst();
    }

    public enum Kind { READ_ONLY_DISCOVERY, MATERIAL_CONSTRUCTION }
}
