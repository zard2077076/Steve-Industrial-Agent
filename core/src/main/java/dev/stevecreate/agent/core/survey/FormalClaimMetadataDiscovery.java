package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.deployment.PermissionDecision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Pure allowlisted metadata discovery; no file, credential, database or claim API access. */
public final class FormalClaimMetadataDiscovery {
    private static final Set<String> KNOWN_CLAIM_MODS = Set.of(
            "openpartiesandclaims", "ftbchunks", "flan", "openpac");

    public ClaimPermissionDiscoveryResult discover(List<ClaimMetadataDocument> input) {
        List<ClaimMetadataDocument> documents = List.copyOf(input);
        if (documents.size() > 128) throw new IllegalArgumentException("too many claim metadata documents");
        documents.forEach(FormalClaimMetadataDiscovery::validateSource);

        Map<String, MutableDiscovery> detected = new TreeMap<>();
        documents.stream().filter(FormalClaimMetadataDiscovery::isDescriptor).forEach(document -> {
            String modId = required(document, "modId");
            if (!KNOWN_CLAIM_MODS.contains(modId)) return;
            String version = required(document, "version");
            String displayName = required(document, "displayName");
            MutableDiscovery discovery = detected.computeIfAbsent(modId,
                    ignored -> new MutableDiscovery(modId, version, displayName));
            if (!discovery.version.equals(version) || !discovery.displayName.equals(displayName)) {
                throw new IllegalArgumentException("conflicting claim mod descriptor metadata");
            }
            discovery.metadataSources.add(document.relativeSourcePath());
        });

        documents.stream().filter(document -> !isDescriptor(document)).forEach(document -> {
            String modId = document.metadata().get("modId");
            MutableDiscovery discovery = detected.get(modId);
            if (discovery == null) return;
            discovery.metadataSources.add(document.relativeSourcePath());
            add(discovery.configSources, document.metadata().get("configPath"));
            add(discovery.dataSources, document.metadata().get("dataSource"));
            add(discovery.apiHints, document.metadata().get("apiHint"));
        });

        List<ClaimModDiscovery> mods = detected.values().stream().map(MutableDiscovery::freeze)
                .sorted(Comparator.comparing(ClaimModDiscovery::modId)).toList();
        List<String> tasks = mods.stream().map(mod ->
                "IMPLEMENT_READ_ONLY_CLAIM_ADAPTER:" + mod.modId() + ":" + mod.version()).toList();
        List<SurveyLimitation> limitations = new ArrayList<>();
        if (mods.isEmpty()) {
            limitations.add(new SurveyLimitation("CLAIM_PERMISSION_UNKNOWN", "formal-world",
                    "No supported claim metadata was detected; formal permission remains unknown", true, true));
        } else {
            mods.forEach(mod -> limitations.add(new SurveyLimitation("CLAIM_ADAPTER_REQUIRED", mod.modId(),
                    "Claim mod metadata was detected but no formal read-only Adapter is implemented", true, true)));
        }
        return new ClaimPermissionDiscoveryResult(mods, PermissionDecision.UNKNOWN, tasks, limitations,
                false, false, false, false, false);
    }

    private static void validateSource(ClaimMetadataDocument document) {
        String path = document.relativeSourcePath();
        boolean descriptor = path.matches("mods/[A-Za-z0-9._+-]+\\.jar!/META-INF/mods\\.toml");
        boolean config = path.matches("(?:config|serverconfig)/[A-Za-z0-9._+/-]+\\.(?:toml|json|conf)");
        if (!descriptor && !config || path.toLowerCase().matches(
                ".*(?:token|credential|password|auth|chat|playerdata|stats|advancements|database|sqlite).*")) {
            throw new IllegalArgumentException("claim metadata source is outside the allowlist");
        }
    }

    private static boolean isDescriptor(ClaimMetadataDocument document) {
        return document.relativeSourcePath().contains(".jar!/META-INF/mods.toml");
    }

    private static String required(ClaimMetadataDocument document, String key) {
        String value = document.metadata().get(key);
        if (value == null) throw new IllegalArgumentException("claim descriptor is missing " + key);
        return value;
    }

    private static void add(TreeSet<String> target, String value) {
        if (value != null) target.add(value);
    }

    private static final class MutableDiscovery {
        private final String modId;
        private final String version;
        private final String displayName;
        private final TreeSet<String> metadataSources = new TreeSet<>();
        private final TreeSet<String> configSources = new TreeSet<>();
        private final TreeSet<String> dataSources = new TreeSet<>();
        private final TreeSet<String> apiHints = new TreeSet<>();

        private MutableDiscovery(String modId, String version, String displayName) {
            this.modId = modId;
            this.version = version;
            this.displayName = displayName;
        }

        private ClaimModDiscovery freeze() {
            return new ClaimModDiscovery(modId, version, displayName, List.copyOf(metadataSources),
                    List.copyOf(configSources), List.copyOf(dataSources), List.copyOf(apiHints), false, false);
        }
    }
}
