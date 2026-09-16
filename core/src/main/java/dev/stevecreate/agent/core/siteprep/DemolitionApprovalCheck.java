package dev.stevecreate.agent.core.siteprep;

import java.util.List;

public record DemolitionApprovalCheck(
        boolean accepted,
        List<DemolitionApprovalFailure> failures) {
    public DemolitionApprovalCheck {
        failures = List.copyOf(failures);
        if (accepted == !failures.isEmpty()) {
            throw new IllegalArgumentException("accepted and failures are inconsistent");
        }
    }
}
