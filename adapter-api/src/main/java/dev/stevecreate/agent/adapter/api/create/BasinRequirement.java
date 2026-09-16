package dev.stevecreate.agent.adapter.api.create;

/** Basin capacity and heat-position facts required by C-08/C-09. */
public record BasinRequirement(
        boolean required,
        int maximumItemInputs,
        int maximumFluidInputs,
        boolean heatSourceObservationRequired,
        boolean outputCapacityObservationRequired) {
    public BasinRequirement {
        if (maximumItemInputs < 0 || maximumItemInputs > 64
                || maximumFluidInputs < 0 || maximumFluidInputs > 16) {
            throw new IllegalArgumentException("Basin input bounds are invalid");
        }
        if (required) {
            if (maximumItemInputs < 1 || !outputCapacityObservationRequired) {
                throw new IllegalArgumentException(
                        "A required basin needs item capacity and live output-capacity evidence");
            }
        } else if (maximumItemInputs != 0 || maximumFluidInputs != 0
                || heatSourceObservationRequired || outputCapacityObservationRequired) {
            throw new IllegalArgumentException("A non-basin process cannot claim basin facts");
        }
    }

    public static BasinRequirement none() {
        return new BasinRequirement(false, 0, 0, false, false);
    }
}
