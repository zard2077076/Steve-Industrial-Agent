package dev.stevecreate.agent.core.deployment;

/** Non-forgeable outside this package; later readiness may consume it only for an isolated environment. */
public final class DeploymentWritePermit implements DeploymentGuardResult {
    private final DeploymentWriteRequest request;

    DeploymentWritePermit(DeploymentWriteRequest request) {
        this.request = request;
    }

    public DeploymentWriteRequest request() { return request; }
}
