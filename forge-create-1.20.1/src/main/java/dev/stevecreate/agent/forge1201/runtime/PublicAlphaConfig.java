package dev.stevecreate.agent.forge1201.runtime;

import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;

/** Forge-owned public runtime configuration. Safe defaults never enable pilot writes. */
public final class PublicAlphaConfig {
    public static final int CURRENT_SCHEMA = 1;
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue SCHEMA_VERSION;
    public static final ForgeConfigSpec.ConfigValue<String> RUNTIME_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> TEST_INSTANCE_ROOT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_TEST_WORLDS;
    public static final ForgeConfigSpec.ConfigValue<String> BACKUP_ROOT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> IMPORTANT_INSTANCE_ROOTS;
    public static final ForgeConfigSpec.IntValue MAX_REGION_SIZE;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_BACKUP;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_WORLD_MARKER;
    public static final ForgeConfigSpec.BooleanValue ALLOW_DIRECT_PILOT;
    public static final ForgeConfigSpec.BooleanValue DIAGNOSTICS_REDACTION;
    public static final ForgeConfigSpec.ConfigValue<String> FORMAL_WORLD_POLICY;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Steve Industrial Agent public alpha runtime. Defaults fail closed.")
                .push("runtime");
        SCHEMA_VERSION = builder.defineInRange("configSchemaVersion", CURRENT_SCHEMA, 0, CURRENT_SCHEMA);
        RUNTIME_MODE = builder.define("runtimeMode", "SETUP_REQUIRED");
        TEST_INSTANCE_ROOT = builder.define("testInstanceRoot", "");
        ALLOWED_TEST_WORLDS = builder.defineListAllowEmpty(
                "allowedTestWorlds", List::of, value -> value instanceof String);
        BACKUP_ROOT = builder.define("backupRoot", "");
        IMPORTANT_INSTANCE_ROOTS = builder.defineListAllowEmpty(
                "importantInstanceRoots", List::of, value -> value instanceof String);
        MAX_REGION_SIZE = builder.defineInRange("maxRegionSize", 32, 8, 64);
        REQUIRE_BACKUP = builder.define("requireBackup", true);
        REQUIRE_WORLD_MARKER = builder.define("requireWorldMarker", true);
        ALLOW_DIRECT_PILOT = builder.define("allowDirectPilot", false);
        DIAGNOSTICS_REDACTION = builder.define("diagnosticsRedaction", true);
        FORMAL_WORLD_POLICY = builder.define("formalWorldPolicy", "DENY_CONFIGURED_ROOTS");
        builder.pop();
        SPEC = builder.build();
    }

    private PublicAlphaConfig() {}
}
