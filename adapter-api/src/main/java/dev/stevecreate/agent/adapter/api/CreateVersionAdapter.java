package dev.stevecreate.agent.adapter.api;

/** Version boundary for all Forge/Create world access. */
public interface CreateVersionAdapter extends IndustrialModAdapter {
    @Override
    default String targetModId() {
        return "create";
    }

    /** Point capture of Create kinetic state; implementations must not traverse the network graph. */
    AdapterResult<KineticSnapshot> captureKinetics(KineticCaptureRequest request);
}
