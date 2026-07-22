package dev.stevecreate.agent.core.survey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class OfflineNbtCompression {
    private OfflineNbtCompression() {}

    static byte[] decompress(byte[] compressed, int compressionType, NbtReadLimits limits)
            throws FormalNbtException {
        if (compressed.length > limits.maxCompressedNbtBytes()) {
            throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE,
                    "compressed NBT exceeds configured maximum");
        }
        try {
            InputStream raw = new ByteArrayInputStream(compressed);
            InputStream decoded = switch (compressionType) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> throw new FormalNbtException(
                        FormalSurveyFailureCode.CHUNK_COMPRESSION_UNSUPPORTED,
                        "unsupported chunk compression type " + compressionType);
            };
            try (decoded; ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(Math.max(compressed.length, 32), 1_048_576))) {
                byte[] buffer = new byte[64 * 1_024];
                int total = 0;
                int count;
                while ((count = decoded.read(buffer)) != -1) {
                    total = Math.addExact(total, count);
                    if (total > limits.maxUncompressedNbtBytes()) {
                        throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE,
                                "decompressed NBT exceeds configured maximum");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } catch (FormalNbtException exception) {
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_CORRUPT,
                    "compressed NBT could not be decoded", exception);
        }
    }
}
