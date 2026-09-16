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
                && isCreate606(actualCreate);
        return new Result(compatible, actualMinecraft, actualForge, actualCreate);
    }

    /**
     * Create 6.0.6, with or without the build number it actually reports.
     *
     * <p>This compared for exact equality against "6.0.6" and the mod reports
     * "6.0.6-150", so the command told every real installation it was incompatible while
     * the unit test passed — because the test supplied "6.0.6", a string the runtime
     * never produces. Forge was already handled this way one line above.
     *
     * <p>The build suffix has to be split on the hyphen rather than matched as a prefix:
     * a bare startsWith would also accept 6.0.60.</p>
     */
    private static boolean isCreate606(String actual) {
        if (actual.equals("6.0.6")) return true;
        int suffix = actual.indexOf('-');
        return suffix > 0 && actual.substring(0, suffix).equals("6.0.6");
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
