package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Project-aware exact layer over the original aggregate reservation contract.
 * It owns slot identity, project competition and the durable transaction state machine.
 */
public final class ProjectMaterialLedger {
    public static final int MAX_SOURCES_PER_PROJECT = 8;
    private final Map<ResourceId, MaterialRequirementPlan> plans = new LinkedHashMap<>();
    private final Map<ResourceId, MaterialSourceBinding> sources = new LinkedHashMap<>();
    private final Map<ResourceId, MaterialAllocation> allocations = new LinkedHashMap<>();
    private final Map<ResourceId, MaterialTransaction> transactions = new LinkedHashMap<>();
    private final Set<ResourceId> releasedAllocations = new LinkedHashSet<>();
    private final List<String> evidence = new ArrayList<>();
    private long generation;
    private long lastTick;

    public synchronized void registerPlan(MaterialRequirementPlan plan) {
        Objects.requireNonNull(plan, "plan");
        MaterialRequirementPlan existing = plans.putIfAbsent(plan.projectId(), plan);
        if (existing != null && !existing.equals(plan)) {
            throw new IllegalArgumentException("project already has a different material plan");
        }
    }

    public synchronized void bindSource(MaterialSourceBinding source, long tick) {
        requireTick(tick);
        Objects.requireNonNull(source, "source");
        if (!plans.containsKey(source.projectId())) {
            throw new IllegalArgumentException("material source has no registered project plan");
        }
        long sourceCount = sources.values().stream()
                .filter(value -> value.projectId().equals(source.projectId())).count();
        MaterialSourceBinding existing = sources.get(source.sourceId());
        if (existing == null && sourceCount >= MAX_SOURCES_PER_PROJECT) {
            throw new IllegalStateException("project reached the material source bound");
        }
        if (existing != null && !existing.equals(source)) {
            throw new IllegalArgumentException("material source identity already exists");
        }
        sources.putIfAbsent(source.sourceId(), source);
        lastTick = tick;
    }

    public synchronized MaterialAllocation reserve(MaterialAllocation allocation, long tick) {
        requireTick(tick);
        Objects.requireNonNull(allocation, "allocation");
        MaterialAllocation existing = allocations.get(allocation.allocationId());
        if (existing != null) {
            if (existing.equals(allocation)) return existing;
            throw new IllegalArgumentException("allocation identity conflict");
        }
        MaterialRequirementPlan plan = requirePlan(allocation.projectId());
        MaterialRequirement requirement = plan.requirements().stream()
                .filter(value -> value.requirementId().equals(allocation.requirementId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown material requirement"));
        MaterialSourceBinding source = sources.get(allocation.sourceId());
        if (source == null || !source.projectId().equals(allocation.projectId())) {
            throw new IllegalArgumentException("allocation source is not bound to the project");
        }
        if (tick >= source.expiresAtEpochMillis()) {
            throw new IllegalStateException("material source reservation authority expired");
        }
        if (!requirement.acceptedResources().contains(allocation.identity().itemId())) {
            throw new IllegalArgumentException("allocation item is not accepted by the requirement");
        }
        MaterialSlotSnapshot slot = source.slots().stream()
                .filter(value -> value.slot() == allocation.sourceSlot())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("allocation slot is absent"));
        if (!slot.identity().equals(allocation.identity())) {
            throw new IllegalArgumentException("allocation identity differs from the source snapshot");
        }
        long requirementCommitted = allocations.values().stream()
                .filter(value -> !releasedAllocations.contains(value.allocationId()))
                .filter(value -> value.projectId().equals(allocation.projectId())
                        && value.requirementId().equals(allocation.requirementId()))
                .mapToLong(MaterialAllocation::quantity).sum();
        if (Math.addExact(requirementCommitted, allocation.quantity()) > requirement.quantity()) {
            throw new IllegalStateException("allocation exceeds the material requirement");
        }
        long slotCommitted = allocations.values().stream()
                .filter(value -> !releasedAllocations.contains(value.allocationId()))
                .filter(value -> physicalKey(sources.get(value.sourceId())).equals(physicalKey(source))
                        && value.sourceSlot() == allocation.sourceSlot()
                        && value.identity().equals(allocation.identity()))
                .mapToLong(MaterialAllocation::quantity).sum();
        if (Math.addExact(slotCommitted, allocation.quantity()) > slot.quantity()) {
            throw new IllegalStateException("physical source slot is already reserved");
        }
        long sourceCommitted = allocations.values().stream()
                .filter(value -> !releasedAllocations.contains(value.allocationId()))
                .filter(value -> value.sourceId().equals(source.sourceId()))
                .mapToLong(MaterialAllocation::quantity).sum();
        if (Math.addExact(sourceCommitted, allocation.quantity()) > source.maximumWithdrawal()) {
            throw new IllegalStateException("allocation exceeds the source withdrawal cap");
        }
        allocations.put(allocation.allocationId(), allocation);
        evidence("RESERVED", allocation.allocationId(), tick);
        lastTick = tick;
        return allocation;
    }

    public synchronized MaterialTransaction prepare(
            ResourceId transactionId,
            ResourceId projectId,
            ResourceId taskId,
            ResourceId allocationId,
            MaterialExecutorKind executor,
            long quantity,
            long tick) {
        requireTick(tick);
        MaterialTransaction existing = transactions.get(Objects.requireNonNull(transactionId, "transactionId"));
        if (existing != null) {
            if (existing.projectId().equals(projectId) && existing.taskId().equals(taskId)
                    && existing.allocationId().equals(allocationId) && existing.executor() == executor
                    && existing.quantity() == quantity) return existing;
            throw new IllegalArgumentException("transaction identity conflict");
        }
        MaterialAllocation allocation = allocations.get(Objects.requireNonNull(allocationId, "allocationId"));
        if (allocation == null || releasedAllocations.contains(allocationId)
                || !allocation.projectId().equals(projectId) || quantity < 1
                || quantity > remainingTransactionQuantity(allocationId)) {
            throw new IllegalStateException("transaction exceeds an active allocation");
        }
        MaterialTransaction created = new MaterialTransaction(transactionId, projectId, taskId,
                allocationId, executor, quantity, MaterialTransactionState.PREPARED,
                nextGeneration(), tick);
        transactions.put(transactionId, created);
        evidence("PREPARED", transactionId, tick);
        lastTick = tick;
        return created;
    }

    public synchronized MaterialTransaction advance(
            ResourceId transactionId, MaterialTransactionState next, long tick) {
        requireTick(tick);
        MaterialTransaction current = transactions.get(Objects.requireNonNull(transactionId, "transactionId"));
        if (current == null) throw new IllegalArgumentException("unknown material transaction");
        if (current.state() == next) return current;
        MaterialTransaction updated = current.advance(next, nextGeneration(), tick);
        transactions.put(transactionId, updated);
        evidence(next.name(), transactionId, tick);
        lastTick = tick;
        return updated;
    }

    public synchronized void releaseAllocation(ResourceId allocationId, long tick) {
        requireTick(tick);
        MaterialAllocation allocation = allocations.get(Objects.requireNonNull(allocationId, "allocationId"));
        if (allocation == null) throw new IllegalArgumentException("unknown material allocation");
        boolean hasMaterial = transactions.values().stream()
                .filter(value -> value.allocationId().equals(allocationId))
                .anyMatch(value -> value.state() != MaterialTransactionState.PREPARED
                        && value.state() != MaterialTransactionState.RELEASED
                        && value.state() != MaterialTransactionState.RETURNED);
        if (hasMaterial) throw new IllegalStateException("withdrawn material must be returned before release");
        releasedAllocations.add(allocationId);
        evidence("ALLOCATION_RELEASED", allocationId, tick);
        lastTick = tick;
    }

    public synchronized boolean fullyReserved(ResourceId projectId) {
        MaterialRequirementPlan plan = requirePlan(projectId);
        return plan.requirements().stream().allMatch(requirement -> allocations.values().stream()
                .filter(value -> !releasedAllocations.contains(value.allocationId()))
                .filter(value -> value.projectId().equals(projectId)
                        && value.requirementId().equals(requirement.requirementId()))
                .mapToLong(MaterialAllocation::quantity).sum() == requirement.quantity());
    }

    public synchronized MaterialBalanceReport balance(ResourceId projectId) {
        MaterialRequirementPlan plan = requirePlan(projectId);
        long planned = plan.requirements().stream().mapToLong(MaterialRequirement::quantity).sum();
        long reserved = allocations.values().stream()
                .filter(value -> value.projectId().equals(projectId)
                        && !releasedAllocations.contains(value.allocationId()))
                .mapToLong(MaterialAllocation::quantity).sum();
        List<MaterialTransaction> rows = transactions.values().stream()
                .filter(value -> value.projectId().equals(projectId)).toList();
        long withdrawn = rows.stream().filter(value -> switch (value.state()) {
            case WITHDRAWN, DELIVERED, CONSUMED, RETURN_PENDING, RETURNED -> true;
            case PREPARED, RELEASED -> false;
        }).mapToLong(MaterialTransaction::quantity).sum();
        long delivered = rows.stream().filter(value -> value.state() == MaterialTransactionState.DELIVERED
                        || value.state() == MaterialTransactionState.CONSUMED)
                .mapToLong(MaterialTransaction::quantity).sum();
        long consumed = rows.stream().filter(value -> value.state() == MaterialTransactionState.CONSUMED)
                .mapToLong(MaterialTransaction::quantity).sum();
        long returned = rows.stream().filter(value -> value.state() == MaterialTransactionState.RETURNED)
                .mapToLong(MaterialTransaction::quantity).sum();
        long outstanding = rows.stream().filter(value -> value.state() == MaterialTransactionState.WITHDRAWN
                        || value.state() == MaterialTransactionState.DELIVERED
                        || value.state() == MaterialTransactionState.RETURN_PENDING)
                .mapToLong(MaterialTransaction::quantity).sum();
        long unaccounted = Math.max(0, withdrawn - consumed - returned - outstanding);
        boolean balanced = planned == reserved
                && withdrawn == consumed + returned + outstanding
                && unaccounted == 0;
        return new MaterialBalanceReport(planned, reserved, withdrawn, delivered, consumed,
                returned, outstanding, 0, 0, unaccounted, balanced);
    }

    public synchronized ProjectMaterialLedgerSnapshot snapshot(long tick) {
        requireTick(tick);
        lastTick = tick;
        return new ProjectMaterialLedgerSnapshot(plans, sources, allocations, transactions,
                releasedAllocations, generation, tick, evidence);
    }

    public static ProjectMaterialLedger restore(ProjectMaterialLedgerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ProjectMaterialLedger ledger = new ProjectMaterialLedger();
        ledger.plans.putAll(snapshot.plans());
        ledger.sources.putAll(snapshot.sources());
        ledger.allocations.putAll(snapshot.allocations());
        ledger.transactions.putAll(snapshot.transactions());
        ledger.releasedAllocations.addAll(snapshot.releasedAllocations());
        ledger.generation = snapshot.generation();
        ledger.lastTick = snapshot.savedTick();
        ledger.evidence.addAll(snapshot.evidence());
        ledger.validateRestored();
        return ledger;
    }

    public synchronized Map<ResourceId, MaterialSourceBinding> sources() { return Map.copyOf(sources); }
    public synchronized Map<ResourceId, MaterialAllocation> allocations() { return Map.copyOf(allocations); }
    public synchronized Map<ResourceId, MaterialTransaction> transactions() { return Map.copyOf(transactions); }

    private void validateRestored() {
        if (transactions.values().stream().anyMatch(value -> value.generation() > generation
                || value.updatedTick() > lastTick)) {
            throw new IllegalArgumentException("restored transaction is newer than its snapshot");
        }
        for (MaterialAllocation allocation : allocations.values()) {
            if (!plans.containsKey(allocation.projectId()) || !sources.containsKey(allocation.sourceId())) {
                throw new IllegalArgumentException("restored allocation has missing authority");
            }
        }
        for (MaterialTransaction transaction : transactions.values()) {
            MaterialAllocation allocation = allocations.get(transaction.allocationId());
            if (allocation == null || !allocation.projectId().equals(transaction.projectId())) {
                throw new IllegalArgumentException("restored transaction has missing allocation");
            }
        }
        plans.keySet().forEach(this::balance);
    }

    private long remainingTransactionQuantity(ResourceId allocationId) {
        MaterialAllocation allocation = allocations.get(allocationId);
        long committed = transactions.values().stream()
                .filter(value -> value.allocationId().equals(allocationId))
                .filter(value -> value.state() != MaterialTransactionState.RELEASED)
                .mapToLong(MaterialTransaction::quantity).sum();
        return allocation.quantity() - committed;
    }

    private MaterialRequirementPlan requirePlan(ResourceId projectId) {
        MaterialRequirementPlan plan = plans.get(Objects.requireNonNull(projectId, "projectId"));
        if (plan == null) throw new IllegalArgumentException("unknown material project");
        return plan;
    }

    private static String physicalKey(MaterialSourceBinding source) {
        if (source == null) return "missing";
        return source.dimension() + "|" + source.position();
    }

    private void evidence(String event, ResourceId identity, long tick) {
        if (evidence.size() >= 4_096) throw new IllegalStateException("material evidence bound reached");
        evidence.add(generation + "|" + tick + "|" + event + "|" + identity);
    }

    private long nextGeneration() { return generation = Math.addExact(generation, 1); }

    private void requireTick(long tick) {
        if (tick < lastTick) throw new IllegalArgumentException("material ledger tick cannot move backwards");
    }
}
