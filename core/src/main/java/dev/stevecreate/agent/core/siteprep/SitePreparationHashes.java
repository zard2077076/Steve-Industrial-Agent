package dev.stevecreate.agent.core.siteprep;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

final class SitePreparationHashes {
    private SitePreparationHashes() {}

    static String sha256(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        String copy = value.trim();
        if (copy.isEmpty() || copy.length() > 512 || copy.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return copy;
    }

    static String hash(String value, String name) {
        Objects.requireNonNull(value, name);
        String copy = value.startsWith("sha256:") ? value.substring(7) : value;
        if (!copy.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is not a SHA-256 value");
        }
        return copy;
    }
}
