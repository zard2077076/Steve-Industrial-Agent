package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Explicit capability availability and ordered mod preference used only for scoring. */
public record PlanScoringContext(
        Set<ResourceId> availableCapabilityIds,
        List<String> preferredModIds) {
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public PlanScoringContext {
        Objects.requireNonNull(availableCapabilityIds, "availableCapabilityIds");
        if (availableCapabilityIds.size() > MachineCapabilityCatalogLimit.MAX_CAPABILITIES) {
            throw new IllegalArgumentException("availableCapabilityIds exceeds its bound");
        }
        TreeSet<ResourceId> capabilities = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (ResourceId value : availableCapabilityIds) {
            capabilities.add(Objects.requireNonNull(value, "availableCapabilityIds element"));
        }
        availableCapabilityIds = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));

        Objects.requireNonNull(preferredModIds, "preferredModIds");
        if (preferredModIds.size() > ProductionGoal.MAX_MOD_CONSTRAINTS) {
            throw new IllegalArgumentException("preferredModIds exceeds its bound");
        }
        List<String> mods = new ArrayList<>(preferredModIds.size());
        Set<String> unique = new HashSet<>();
        for (String value : preferredModIds) {
            String modId = Objects.requireNonNull(value, "preferredModIds element");
            if (!MOD_ID.matcher(modId).matches() || !unique.add(modId)) {
                throw new IllegalArgumentException("Invalid or duplicate preferred mod ID: " + modId);
            }
            mods.add(modId);
        }
        preferredModIds = List.copyOf(mods);
    }

    /** Keeps this model independent of the concrete immutable catalog implementation constant. */
    private static final class MachineCapabilityCatalogLimit {
        private static final int MAX_CAPABILITIES = ImmutableMachineCapabilityCatalog.MAX_CAPABILITIES;
    }
}
