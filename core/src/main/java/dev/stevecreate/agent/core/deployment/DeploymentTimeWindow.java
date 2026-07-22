package dev.stevecreate.agent.core.deployment;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record DeploymentTimeWindow(Instant startsAt, Instant endsAt) {
    public DeploymentTimeWindow {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        if (!endsAt.isAfter(startsAt) || Duration.between(startsAt, endsAt).compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("Deployment time window is invalid or too large");
        }
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(startsAt) && instant.isBefore(endsAt);
    }
}
