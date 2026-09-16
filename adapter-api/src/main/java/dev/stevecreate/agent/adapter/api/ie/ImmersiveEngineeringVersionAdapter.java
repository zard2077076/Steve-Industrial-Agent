package dev.stevecreate.agent.adapter.api.ie;

import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.adapter.api.IndustrialModAdapter;
import dev.stevecreate.agent.adapter.api.ScanRequest;
import dev.stevecreate.agent.core.electrical.ElectricalNetworkGraph;
import dev.stevecreate.agent.core.industrial.IndustrialCapabilityProfile;
import java.util.List;

/** Versioned IE boundary; implementation-internal game APIs never escape these immutable values. */
public interface ImmersiveEngineeringVersionAdapter extends IndustrialModAdapter {
    String supportedImmersiveEngineeringVersion();

    IndustrialCapabilityProfile capabilityProfile();

    /** Reviewed orders whose endpoints this adapter can re-observe after restart. */
    List<ImmersiveEngineeringResourceRegistration> resourceRecoveryRegistrations();

    AdapterResult<ImmersiveEngineeringRecipeCatalogSnapshot> captureRecipeCatalog(
            ScanRequest request);

    AdapterResult<ImmersiveEngineeringMultiblockCatalogSnapshot> captureMultiblockCatalog(
            ScanRequest request);

    AdapterResult<ElectricalNetworkGraph> captureElectricalNetwork(ScanRequest request);

    AdapterResult<ImmersiveEngineeringActionEvidence> execute(
            ImmersiveEngineeringActionRequest request);
}
