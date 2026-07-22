package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.nio.channels.FileChannel;

@FunctionalInterface
public interface GuardedFormalRead<T> {
    T read(FileChannel channel) throws IOException;
}
