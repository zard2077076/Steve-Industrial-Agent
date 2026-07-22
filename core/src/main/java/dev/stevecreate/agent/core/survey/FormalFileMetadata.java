package dev.stevecreate.agent.core.survey;

record FormalFileMetadata(long sizeBytes, long lastModifiedEpochMillis, boolean privateContent) {
    FormalFileMetadata {
        if (sizeBytes < 0 || lastModifiedEpochMillis < 0) {
            throw new IllegalArgumentException("formal file metadata is negative");
        }
    }
}
