package dev.stevecreate.agent.core.formalbackup;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;

/** Exact canonical JSON codec; version strings are base64url so arbitrary text cannot alter structure. */
public final class FormalBackupCompletionMarkerCodec {
    private static final String FORMAT = "formal-backup-completion-v1";
    private static final int MAX_BYTES = 64 * 1_024;

    public byte[] encode(FormalBackupCompletionMarker marker) {
        Objects.requireNonNull(marker, "marker");
        String json = "{\n"
                + "  \"format\": \"" + FORMAT + "\",\n"
                + "  \"state\": \"" + marker.state() + "\",\n"
                + "  \"backupIdentity\": \"" + marker.backupIdentity() + "\",\n"
                + "  \"planIdentity\": \"" + marker.planIdentity() + "\",\n"
                + "  \"worldIdentity\": \"" + marker.worldIdentity() + "\",\n"
                + "  \"sourceFingerprint\": \"" + marker.sourceFingerprint() + "\",\n"
                + "  \"runtimeFingerprint\": \"" + marker.runtimeFingerprint() + "\",\n"
                + "  \"manifestHash\": \"" + marker.manifestHash() + "\",\n"
                + "  \"fileCount\": " + marker.fileCount() + ",\n"
                + "  \"totalBytes\": " + marker.totalBytes() + ",\n"
                + "  \"completedAt\": \"" + marker.completedAt() + "\",\n"
                + "  \"policyVersionBase64\": \"" + base64(marker.policyVersion()) + "\",\n"
                + "  \"toolVersionBase64\": \"" + base64(marker.toolVersion()) + "\",\n"
                + "  \"gitHead\": \"" + marker.gitHead() + "\"\n"
                + "}\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("completion marker is too large");
        return bytes;
    }

    public FormalBackupCompletionMarker decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("completion marker size is invalid");
        }
        String[] lines = new String(encoded, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length != 17 || !lines[0].equals("{") || !lines[15].equals("}")
                || !lines[16].isEmpty()) {
            throw new IllegalArgumentException("completion marker structure is invalid");
        }
        if (!string(lines[1], "format", true).equals(FORMAT)) {
            throw new IllegalArgumentException("completion marker format is invalid");
        }
        try {
            FormalBackupCompletionMarker marker = new FormalBackupCompletionMarker(
                    FormalBackupCompletionState.valueOf(string(lines[2], "state", true)),
                    string(lines[3], "backupIdentity", true),
                    string(lines[4], "planIdentity", true),
                    string(lines[5], "worldIdentity", true),
                    string(lines[6], "sourceFingerprint", true),
                    string(lines[7], "runtimeFingerprint", true),
                    string(lines[8], "manifestHash", true),
                    longNumber(lines[9], "fileCount", true),
                    longNumber(lines[10], "totalBytes", true),
                    Instant.parse(string(lines[11], "completedAt", true)),
                    unbase64(string(lines[12], "policyVersionBase64", true)),
                    unbase64(string(lines[13], "toolVersionBase64", true)),
                    string(lines[14], "gitHead", false));
            if (!java.util.Arrays.equals(encoded, encode(marker))) {
                throw new IllegalArgumentException("completion marker is non-canonical");
            }
            return marker;
        } catch (DateTimeParseException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("completion marker value is invalid", exception);
        }
    }

    private static String string(String line, String name, boolean comma) {
        String prefix = "  \"" + name + "\": \"";
        String suffix = comma ? "\"," : "\"";
        if (!line.startsWith(prefix) || !line.endsWith(suffix)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return line.substring(prefix.length(), line.length() - suffix.length());
    }

    private static long longNumber(String line, String name, boolean comma) {
        String prefix = "  \"" + name + "\": ";
        String suffix = comma ? "," : "";
        if (!line.startsWith(prefix) || !line.endsWith(suffix)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        try {
            return Long.parseLong(line.substring(prefix.length(), line.length() - suffix.length()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is invalid", exception);
        }
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(String value) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (!base64(decoded).equals(value)) throw new IllegalArgumentException("base64 is non-canonical");
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("base64 is invalid", exception);
        }
    }
}
