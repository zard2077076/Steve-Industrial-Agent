package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Read-only installed/supported runtime declarations supplied to the pure planner. */
public record PlanningContext(
        Set<ResourceId> availableAdapterIds,
        Set<String> availableModIds,
        Set<GenericResourceType> supportedResourceTypes) {
    public static final int MAX_ADAPTERS = 64;
    public static final int MAX_MODS = 128;
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public PlanningContext {
        Objects.requireNonNull(availableAdapterIds, "availableAdapterIds");
        if (availableAdapterIds.size() > MAX_ADAPTERS) {
            throw new IllegalArgumentException("availableAdapterIds exceeds its bound");
        }
        TreeSet<ResourceId> adapters = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        for (ResourceId value : availableAdapterIds) {
            adapters.add(Objects.requireNonNull(value, "availableAdapterIds element"));
        }
        availableAdapterIds = Collections.unmodifiableSet(new LinkedHashSet<>(adapters));

        Objects.requireNonNull(availableModIds, "availableModIds");
        if (availableModIds.size() > MAX_MODS) {
            throw new IllegalArgumentException("availableModIds exceeds its bound");
        }
        TreeSet<String> mods = new TreeSet<>();
        for (String value : availableModIds) {
            String modId = Objects.requireNonNull(value, "availableModIds element");
            if (!MOD_ID.matcher(modId).matches()) {
                throw new IllegalArgumentException("Invalid available mod ID: " + modId);
            }
            mods.add(modId);
        }
        availableModIds = Collections.unmodifiableSet(new LinkedHashSet<>(mods));

        Objects.requireNonNull(supportedResourceTypes, "supportedResourceTypes");
        EnumSet<GenericResourceType> types = EnumSet.noneOf(GenericResourceType.class);
        for (GenericResourceType value : supportedResourceTypes) {
            types.add(Objects.requireNonNull(value, "supportedResourceTypes element"));
        }
        supportedResourceTypes = Collections.unmodifiableSet(new LinkedHashSet<>(types));
    }
}
