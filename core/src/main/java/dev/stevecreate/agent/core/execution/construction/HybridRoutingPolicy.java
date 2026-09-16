package dev.stevecreate.agent.core.execution.construction;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable player-configurable routing preferences with non-overridable safety classifications. */
public final class HybridRoutingPolicy {
    private final Map<ConstructionTaskClass, HybridTaskRoute> routes;
    private final Set<ConstructionTaskClass> directFallbackClasses;

    public HybridRoutingPolicy(
            Map<ConstructionTaskClass, HybridTaskRoute> routes,
            Set<ConstructionTaskClass> directFallbackClasses) {
        Objects.requireNonNull(routes, "routes");
        EnumMap<ConstructionTaskClass, HybridTaskRoute> copiedRoutes =
                new EnumMap<>(ConstructionTaskClass.class);
        for (Map.Entry<ConstructionTaskClass, HybridTaskRoute> entry : routes.entrySet()) {
            copiedRoutes.put(
                    Objects.requireNonNull(entry.getKey(), "routes key"),
                    Objects.requireNonNull(entry.getValue(), "routes value"));
        }
        if (copiedRoutes.size() != ConstructionTaskClass.values().length) {
            throw new IllegalArgumentException("Every construction task class requires one Hybrid route");
        }
        if (copiedRoutes.get(ConstructionTaskClass.HIGH_RISK_INTERACTION)
                != HybridTaskRoute.REFUSE) {
            throw new IllegalArgumentException("High-risk Hybrid work is permanently refused");
        }
        if (copiedRoutes.get(ConstructionTaskClass.SYSTEM_VERIFICATION)
                != HybridTaskRoute.DIRECT) {
            throw new IllegalArgumentException("System verification must use the trusted Direct backend");
        }
        this.routes = Collections.unmodifiableMap(new LinkedHashMap<>(copiedRoutes));

        Objects.requireNonNull(directFallbackClasses, "directFallbackClasses");
        EnumSet<ConstructionTaskClass> copiedFallbacks = directFallbackClasses.isEmpty()
                ? EnumSet.noneOf(ConstructionTaskClass.class)
                : EnumSet.copyOf(directFallbackClasses);
        if (copiedFallbacks.contains(ConstructionTaskClass.HIGH_RISK_INTERACTION)
                || copiedFallbacks.stream().anyMatch(value ->
                copiedRoutes.get(value) != HybridTaskRoute.BOTS)) {
            throw new IllegalArgumentException(
                    "Direct fallback is allowed only for Bot-preferred non-high-risk classes");
        }
        this.directFallbackClasses = Collections.unmodifiableSet(copiedFallbacks);
    }

    public static HybridRoutingPolicy safeDefaults() {
        EnumMap<ConstructionTaskClass, HybridTaskRoute> routes =
                new EnumMap<>(ConstructionTaskClass.class);
        routes.put(ConstructionTaskClass.ORDINARY_BLOCK, HybridTaskRoute.BOTS);
        routes.put(ConstructionTaskClass.CREATE_MACHINE, HybridTaskRoute.DIRECT);
        routes.put(ConstructionTaskClass.ORIENTATION_SENSITIVE_COMPONENT, HybridTaskRoute.DIRECT);
        routes.put(ConstructionTaskClass.MATERIAL_TRANSPORT, HybridTaskRoute.BOTS);
        routes.put(ConstructionTaskClass.OBSTRUCTION_CLEARANCE, HybridTaskRoute.DIRECT);
        routes.put(ConstructionTaskClass.SYSTEM_VERIFICATION, HybridTaskRoute.DIRECT);
        routes.put(ConstructionTaskClass.TEST_ONLY_RESOURCE, HybridTaskRoute.BOTS);
        routes.put(ConstructionTaskClass.HIGH_RISK_INTERACTION, HybridTaskRoute.REFUSE);
        routes.put(ConstructionTaskClass.SHARED_INFRASTRUCTURE, HybridTaskRoute.DIRECT);
        return new HybridRoutingPolicy(
                routes,
                EnumSet.of(
                        ConstructionTaskClass.ORDINARY_BLOCK,
                        ConstructionTaskClass.MATERIAL_TRANSPORT,
                        ConstructionTaskClass.TEST_ONLY_RESOURCE));
    }

    public HybridTaskRoute routeFor(ConstructionTaskClass taskClass) {
        return routes.get(Objects.requireNonNull(taskClass, "taskClass"));
    }

    public boolean allowsDirectFallback(ConstructionTaskClass taskClass) {
        return directFallbackClasses.contains(Objects.requireNonNull(taskClass, "taskClass"));
    }

    public Map<ConstructionTaskClass, HybridTaskRoute> routes() {
        return routes;
    }

    public Set<ConstructionTaskClass> directFallbackClasses() {
        return directFallbackClasses;
    }
}
