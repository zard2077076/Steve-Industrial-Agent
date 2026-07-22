package dev.stevecreate.agent.adapter.api;

/** Direction of actual rotation in Create's signed local-axis convention. */
public enum KineticRotationDirection {
    STATIONARY,
    POSITIVE,
    NEGATIVE;

    public static KineticRotationDirection fromSignedSpeed(double signedSpeedRpm) {
        if (!Double.isFinite(signedSpeedRpm)) {
            throw new IllegalArgumentException("signedSpeedRpm must be finite");
        }
        if (signedSpeedRpm > 0) {
            return POSITIVE;
        }
        if (signedSpeedRpm < 0) {
            return NEGATIVE;
        }
        return STATIONARY;
    }
}
