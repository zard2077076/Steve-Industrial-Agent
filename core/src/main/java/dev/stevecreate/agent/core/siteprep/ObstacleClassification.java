package dev.stevecreate.agent.core.siteprep;

public enum ObstacleClassification {
    SAFE_NATURAL_CLEARABLE(true),
    CONFIRM_EACH_OR_GROUP(true),
    PROTECTED_NO_AUTOMATIC_REMOVAL(false),
    ENVIRONMENTAL_HAZARD(false),
    UNKNOWN(false);

    private final boolean approvable;

    ObstacleClassification(boolean approvable) {
        this.approvable = approvable;
    }

    public boolean approvable() {
        return approvable;
    }
}
