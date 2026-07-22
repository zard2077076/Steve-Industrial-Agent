package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.nio.file.Path;

/** Offline reader contract. Implementations must open the supplied regular file for READ only. */
@FunctionalInterface
public interface FormalLevelMetadataReader {
    FormalLevelMetadata read(Path levelDat) throws IOException;
}
