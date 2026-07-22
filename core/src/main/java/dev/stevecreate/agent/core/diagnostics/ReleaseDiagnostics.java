package dev.stevecreate.agent.core.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small allowlisted offline diagnostic payload; it has no filesystem or network authority. */
public final class ReleaseDiagnostics {
    private static final Set<String> ALLOWED = Set.of(
            "version", "gitCommit", "minecraft", "forge", "create", "java", "os",
            "network", "telemetry", "dimension", "worldClassification");
    private static final Pattern PRIVATE_PATH = Pattern.compile(
            "(?i).*(?:[a-z]:[\\\\/]|/home/|/users/).*");
    private static final Pattern SECRET = Pattern.compile(
            "(?i).*(?:token|password|passwd|api[_-]?key|secret)[=:].*");

    private ReleaseDiagnostics() {}

    public static String render(Map<String, String> facts) {
        if (facts == null || !ALLOWED.containsAll(facts.keySet())) {
            throw new IllegalArgumentException("Diagnostics facts exceed the public allowlist");
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        ALLOWED.stream().sorted().forEach(key -> {
            if (facts.containsKey(key)) ordered.put(key, sanitize(facts.get(key)));
        });
        List<String> lines = new ArrayList<>();
        lines.add("Steve Industrial Agent diagnostics/v1");
        ordered.forEach((key, value) -> lines.add(key + "=" + value));
        lines.add("redactedFields=absolute paths,tokens,chat,playerdata,world seed,coordinates,container contents,world files");
        lines.add("automaticUpload=false");
        return String.join("\n", lines) + "\n";
    }

    public static byte[] archive(Map<String, String> facts) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                ZipEntry entry = new ZipEntry("diagnostics.txt");
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(render(facts).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return buffer.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create in-memory diagnostics archive", exception);
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "unavailable";
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (oneLine.length() > 256 || PRIVATE_PATH.matcher(oneLine).matches()
                || SECRET.matcher(oneLine).matches()) return "[REDACTED]";
        return oneLine;
    }
}
