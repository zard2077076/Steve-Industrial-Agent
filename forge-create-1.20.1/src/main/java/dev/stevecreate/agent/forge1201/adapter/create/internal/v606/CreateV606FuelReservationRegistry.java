package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.adapter.api.AdapterFailureCode;
import dev.stevecreate.agent.adapter.api.AdapterResult;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Server-thread reservation table for plan-owned ordinary fuel.
 *
 * <p>It never reads a player inventory or arbitrary container. Physical extraction remains in the
 * exact repository resource-buffer adapter and is accounted against one session reservation.</p>
 */
final class CreateV606FuelReservationRegistry {
    private static final Map<SourceKey, List<State>> ACTIVE = new LinkedHashMap<>();

    private CreateV606FuelReservationRegistry() {}

    static synchronized ReserveResult reserve(
            ServerLevel level,
            Create606WorldResourceBuffer buffer,
            CreateV606VerifiedExecutionMetadata metadata,
            long currentTick) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(metadata, "metadata");
        if (!level.getServer().isSameThread()) {
            return new ReserveFailure(
                    "Fuel reservation must run on the authoritative server thread");
        }
        SourceKey key = new SourceKey(
                level,
                ResourceId.parse(level.dimension().location().toString()),
                buffer.position());
        pruneExpired(key, currentTick);
        AdapterResult<Map<ResourceId, Long>> snapshot = buffer.snapshot();
        if (snapshot instanceof AdapterResult.Failure<Map<ResourceId, Long>> failure) {
            return new ReserveFailure(failure.detail());
        }
        Map<ResourceId, Long> available =
                ((AdapterResult.Success<Map<ResourceId, Long>>) snapshot).value();
        Map<ResourceId, Long> requested = new LinkedHashMap<>();
        for (var requirement : metadata.fuelReservations()) {
            requested.merge(
                    requirement.fuel().resourceId(),
                    requirement.fuel().amount(),
                    Math::addExact);
        }
        List<State> existing = ACTIVE.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (Map.Entry<ResourceId, Long> entry : requested.entrySet()) {
            long committed = existing.stream()
                    .filter(state -> state.active
                            && state.fuel.resourceId().equals(entry.getKey()))
                    .mapToLong(state -> state.fuel.amount() - state.consumed)
                    .sum();
            long physical = available.getOrDefault(entry.getKey(), 0L);
            if (physical - committed < entry.getValue()) {
                if (existing.isEmpty()) ACTIVE.remove(key);
                return new ReserveFailure(
                        "Fuel reservation conflict for " + entry.getKey()
                                + ": physical=" + physical
                                + " alreadyReserved=" + committed
                                + " requested=" + entry.getValue());
            }
        }
        List<State> reserved = new ArrayList<>();
        for (var requirement : metadata.fuelReservations()) {
            State state = new State(
                    requirement.reservationId(),
                    metadata.sessionId(),
                    requirement.stepId(),
                    requirement.fuel(),
                    Math.addExact(currentTick, requirement.expiresAfterTicks()),
                    true);
            existing.add(state);
            reserved.add(state);
        }
        return new Reserved(new Access(key, buffer, metadata.sessionId(), reserved));
    }

    static synchronized void clear() {
        ACTIVE.clear();
    }

    sealed interface ReserveResult permits Reserved, ReserveFailure {}

    record Reserved(Access access) implements ReserveResult {
        Reserved {
            Objects.requireNonNull(access, "access");
        }
    }

    record ReserveFailure(String detail) implements ReserveResult {
        ReserveFailure {
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("detail is blank");
        }
    }

    static final class Access implements CreateV606ReservedFuelAccess {
        private final SourceKey key;
        private final Create606WorldResourceBuffer buffer;
        private final ResourceId sessionId;
        private final List<State> states;
        private boolean released;

        private Access(
                SourceKey key,
                Create606WorldResourceBuffer buffer,
                ResourceId sessionId,
                List<State> states) {
            this.key = key;
            this.buffer = buffer;
            this.sessionId = sessionId;
            this.states = List.copyOf(states);
        }

        @Override
        public AdapterResult<ItemStack> extract(
                ProcessResource fuel, long currentTick) {
            synchronized (CreateV606FuelReservationRegistry.class) {
                State state = find(fuel, currentTick);
                if (state == null) {
                    return failure(
                            "No active exact fuel reservation for " + fuel);
                }
                AdapterResult<ItemStack> result = buffer.extractExact(
                        fuel.resourceId(), Math.toIntExact(fuel.amount()));
                if (result instanceof AdapterResult.Success<ItemStack>) {
                    state.withdrawn = Math.addExact(state.withdrawn, fuel.amount());
                }
                return result;
            }
        }

        @Override
        public AdapterResult<Integer> restore(ItemStack fuel, long currentTick) {
            synchronized (CreateV606FuelReservationRegistry.class) {
                if (released || fuel.isEmpty()) {
                    return failure("Fuel reservation is released or restoration is empty");
                }
                ResourceId resource = Create606WorldResourceBuffer.itemId(fuel);
                State state = states.stream()
                        .filter(value -> value.active
                                && value.fuel.resourceId().equals(resource)
                                && value.withdrawn >= fuel.getCount())
                        .findFirst().orElse(null);
                if (state == null || currentTick > state.expiresAtTick) {
                    return failure("Fuel restoration has no matching active reservation");
                }
                AdapterResult<Integer> restored = buffer.insertExact(fuel);
                if (restored instanceof AdapterResult.Success<Integer>) {
                    state.withdrawn -= fuel.getCount();
                }
                return restored;
            }
        }

        @Override
        public void markConsumed(ProcessResource fuel, long currentTick) {
            synchronized (CreateV606FuelReservationRegistry.class) {
                State state = findWithdrawn(fuel, currentTick);
                if (state == null) {
                    throw new IllegalStateException(
                            "Consumed fuel has no active withdrawn reservation");
                }
                state.withdrawn -= fuel.amount();
                state.consumed = Math.addExact(state.consumed, fuel.amount());
                if (state.consumed == state.fuel.amount()) state.active = false;
                removeInactive(key);
            }
        }

        synchronized Optional<String> release(long currentTick) {
            synchronized (CreateV606FuelReservationRegistry.class) {
                if (released) return Optional.empty();
                for (State state : states) {
                    if (state.withdrawn != 0) {
                        return Optional.of(
                                "Fuel reservation cannot release while exact fuel is withdrawn: "
                                        + state.reservationId);
                    }
                    state.active = false;
                }
                released = true;
                removeInactive(key);
                return Optional.empty();
            }
        }

        synchronized boolean consumedAny() {
            return states.stream().anyMatch(state -> state.consumed > 0);
        }

        private State find(ProcessResource fuel, long currentTick) {
            if (released) return null;
            return states.stream()
                    .filter(state -> state.active
                            && state.sessionId.equals(sessionId)
                            && currentTick <= state.expiresAtTick
                            && state.fuel.resourceId().equals(fuel.resourceId())
                            && state.fuel.amount() - state.consumed - state.withdrawn
                                    >= fuel.amount())
                    .findFirst().orElse(null);
        }

        private State findWithdrawn(ProcessResource fuel, long currentTick) {
            if (released) return null;
            return states.stream()
                    .filter(state -> state.active
                            && currentTick <= state.expiresAtTick
                            && state.fuel.resourceId().equals(fuel.resourceId())
                            && state.withdrawn >= fuel.amount())
                    .findFirst().orElse(null);
        }
    }

    private static void pruneExpired(SourceKey key, long tick) {
        List<State> states = ACTIVE.get(key);
        if (states == null) return;
        states.removeIf(state -> state.active
                && state.withdrawn == 0
                && tick > state.expiresAtTick);
        if (states.isEmpty()) ACTIVE.remove(key);
    }

    private static void removeInactive(SourceKey key) {
        List<State> states = ACTIVE.get(key);
        if (states == null) return;
        Iterator<State> iterator = states.iterator();
        while (iterator.hasNext()) {
            State state = iterator.next();
            if (!state.active && state.withdrawn == 0) iterator.remove();
        }
        if (states.isEmpty()) ACTIVE.remove(key);
    }

    private static <T> AdapterResult<T> failure(String detail) {
        return new AdapterResult.Failure<>(
                AdapterFailureCode.PROCESSING_FAILED, detail);
    }

    private record SourceKey(
            ServerLevel level,
            ResourceId dimension,
            BlockPos3i bufferPosition) {
        private SourceKey {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(bufferPosition, "bufferPosition");
        }
    }

    private static final class State {
        private final ResourceId reservationId;
        private final ResourceId sessionId;
        private final ResourceId stepId;
        private final ProcessResource fuel;
        private final long expiresAtTick;
        private boolean active;
        private long withdrawn;
        private long consumed;

        private State(
                ResourceId reservationId,
                ResourceId sessionId,
                ResourceId stepId,
                ProcessResource fuel,
                long expiresAtTick,
                boolean active) {
            this.reservationId = reservationId;
            this.sessionId = sessionId;
            this.stepId = stepId;
            this.fuel = fuel;
            this.expiresAtTick = expiresAtTick;
            this.active = active;
        }
    }
}
