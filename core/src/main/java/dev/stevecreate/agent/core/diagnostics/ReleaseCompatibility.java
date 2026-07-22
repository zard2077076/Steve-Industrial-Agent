package dev.stevecreate.agent.core.diagnostics;

/** Exact public-alpha compatibility decision without loader dependencies. */
public final class ReleaseCompatibility {
    private ReleaseCompatibility() {}

    public static Result evaluate(String minecraft, String forge, String create) {
        String actualMinecraft = normalize(minecraft);
        String actualForge = normalize(forge);
        String actualCreate = normalize(create);
        boolean compatible = "1.20.1".equals(actualMinecraft)
                && actualForge.startsWith("47.4.")
                && "6.0.6".equals(actualCreate);
        return new Result(compatible, actualMinecraft, actualForge, actualCreate);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "not-installed" : value.trim();
    }

    public record Result(boolean compatible, String minecraft, String forge, String create) {
        public String message() {
            return "Compatibility " + (compatible ? "PASS" : "FAIL")
                    + " minecraft=" + minecraft + " expected=1.20.1"
                    + " forge=" + forge + " expected=47.4.x"
                    + " create=" + create + " expected=6.0.6";
        }
    }
}
