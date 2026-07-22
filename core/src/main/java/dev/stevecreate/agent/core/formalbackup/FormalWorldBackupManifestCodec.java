package dev.stevecreate.agent.core.formalbackup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Bounded canonical persistence format for an ignored backup's complete manifest. */
public final class FormalWorldBackupManifestCodec {
    private static final String VERSION = "formal-world-backup-manifest-v1";
    private static final int MAX_ENCODED_BYTES = 256 * 1_024 * 1_024;

    public byte[] encode(FormalWorldBackupManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        StringBuilder out = new StringBuilder();
        out.append(VERSION).append('\n');
        out.append("state\tCOMPLETE\n");
        out.append("manifestHash\t").append(manifest.manifestHash()).append('\n');
        out.append("fileCount\t").append(manifest.entries().size()).append('\n');
        out.append("totalBytes\t").append(manifest.totalBytes()).append('\n');
        out.append("exclusionCount\t").append(manifest.exclusions().size()).append('\n');
        for (FormalBackupFileEntry entry : manifest.entries()) {
            out.append("F\t").append(path(entry.relativePath())).append('\t')
                    .append(entry.sizeBytes()).append('\t')
                    .append(entry.lastModifiedEpochMillis()).append('\t')
                    .append(entry.sha256()).append('\t').append(entry.privacy()).append('\n');
        }
        for (FormalBackupExcludedEntry entry : manifest.exclusions()) {
            out.append("X\t").append(path(entry.relativePath())).append('\t')
                    .append(entry.sizeBytes()).append('\t')
                    .append(entry.lastModifiedEpochMillis()).append('\t')
                    .append(entry.reason()).append('\n');
        }
        byte[] encoded = out.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_ENCODED_BYTES) throw new IllegalArgumentException("manifest encoding is too large");
        return encoded;
    }

    public FormalWorldBackupManifest decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("manifest encoding size is invalid");
        }
        String text = new String(encoded, StandardCharsets.UTF_8);
        if (!text.endsWith("\n") || text.indexOf('\r') >= 0 || text.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("manifest encoding is non-canonical");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length < 6 || !lines[0].equals(VERSION)
                || !lines[1].equals("state\tCOMPLETE")) {
            throw new IllegalArgumentException("manifest header is invalid");
        }
        String expectedHash = field(lines[2], "manifestHash");
        int expectedFiles = integer(field(lines[3], "fileCount"), "fileCount");
        long expectedBytes = longValue(field(lines[4], "totalBytes"), "totalBytes");
        int expectedExclusions = integer(field(lines[5], "exclusionCount"), "exclusionCount");
        if (expectedFiles < 1 || expectedFiles > 1_000_000
                || expectedExclusions < 0 || expectedExclusions > 1_000_000
                || lines.length != 6 + expectedFiles + expectedExclusions) {
            throw new IllegalArgumentException("manifest counts are invalid");
        }
        List<FormalBackupFileEntry> entries = new ArrayList<>(expectedFiles);
        List<FormalBackupExcludedEntry> exclusions = new ArrayList<>(expectedExclusions);
        int cursor = 6;
        for (int index = 0; index < expectedFiles; index++, cursor++) {
            String[] parts = lines[cursor].split("\t", -1);
            if (parts.length != 6 || !parts[0].equals("F")) {
                throw new IllegalArgumentException("manifest file entry is invalid");
            }
            entries.add(new FormalBackupFileEntry(unpath(parts[1]), longValue(parts[2], "size"),
                    longValue(parts[3], "mtime"), parts[4], FormalBackupFilePrivacy.valueOf(parts[5])));
        }
        for (int index = 0; index < expectedExclusions; index++, cursor++) {
            String[] parts = lines[cursor].split("\t", -1);
            if (parts.length != 5 || !parts[0].equals("X")) {
                throw new IllegalArgumentException("manifest exclusion entry is invalid");
            }
            exclusions.add(new FormalBackupExcludedEntry(unpath(parts[1]), longValue(parts[2], "size"),
                    longValue(parts[3], "mtime"), FormalBackupExclusionReason.valueOf(parts[4])));
        }
        FormalWorldBackupManifest manifest = FormalWorldBackupManifest.complete(entries, exclusions);
        if (!manifest.manifestHash().equals(expectedHash)
                || manifest.totalBytes() != expectedBytes) {
            throw new IllegalArgumentException("manifest aggregate evidence is invalid");
        }
        return manifest;
    }

    private static String field(String line, String name) {
        String prefix = name + "\t";
        if (!line.startsWith(prefix) || line.length() == prefix.length()) {
            throw new IllegalArgumentException(name + " field is invalid");
        }
        return line.substring(prefix.length());
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is invalid", exception);
        }
    }

    private static long longValue(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is invalid", exception);
        }
    }

    private static String path(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unpath(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String path = new String(decoded, StandardCharsets.UTF_8);
            if (!path(path).equals(value)) throw new IllegalArgumentException("path encoding is non-canonical");
            return path;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("path encoding is invalid", exception);
        }
    }
}
