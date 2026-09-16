package dev.stevecreate.agent.core.execution.construction;

/** Bounded operation vocabulary shared by Direct, Bot and Hybrid executors. */
public enum TaskKind {
    RESERVE_PLACEMENT(false, false),
    RESERVE_MATERIAL(false, false),
    FETCH_MATERIAL(false, true),
    TRANSPORT_MATERIAL(false, true),
    PLACE_COMPONENT(true, true),
    REMOVE_SESSION_OWNED_COMPONENT(true, false),
    CONNECT_COMPONENTS(true, false),
    SAFE_MACHINE_INTERACTION(true, false),
    VERIFY_STATE(false, false),
    VERIFY_OUTPUT(false, false),
    RETURN_MATERIAL(false, true),
    CLEANUP(true, false),
    RELEASE_RESERVATION(false, false);

    private final boolean worldMutation;
    private final boolean materialOperation;

    TaskKind(boolean worldMutation, boolean materialOperation) {
        this.worldMutation = worldMutation;
        this.materialOperation = materialOperation;
    }

    public boolean mutatesWorld() {
        return worldMutation;
    }

    public boolean operatesOnMaterial() {
        return materialOperation;
    }
}
