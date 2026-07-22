package dev.stevecreate.agent.core.survey;

import java.nio.file.Path;
import java.util.Objects;

/** Guard-enumerated region path paired with its loader-neutral bounded metadata. */
record FormalRegionSource(Path path, SurveyRegionMetadata metadata) {
    FormalRegionSource {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(metadata, "metadata");
    }
}
