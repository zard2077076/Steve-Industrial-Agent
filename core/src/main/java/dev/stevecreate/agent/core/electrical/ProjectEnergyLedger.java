package dev.stevecreate.agent.core.electrical;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Append-only, exact and restart-safe FE settlement ledger. */
public final class ProjectEnergyLedger {
    private final Map<ResourceId, EnergyTransaction> transactions = new LinkedHashMap<>();
    private final List<String> journal = new ArrayList<>();
    private long generation;
    private long lastTick;

    public synchronized EnergyTransaction prepare(
            ResourceId transactionId,
            ResourceId projectId,
            ResourceId taskId,
            ResourceId sourceEndpointId,
            ResourceId energyResource,
            long amountFe,
            long tick) {
        requireTick(tick);
        EnergyTransaction existing = transactions.get(transactionId);
        if (existing != null) {
            if (!existing.projectId().equals(projectId) || !existing.taskId().equals(taskId)
                    || !existing.sourceEndpointId().equals(sourceEndpointId)
                    || !existing.energyResource().equals(energyResource)
                    || existing.amountFe() != amountFe) {
                throw new IllegalStateException("energy transaction identity was reused");
            }
            return existing;
        }
        EnergyTransaction created = new EnergyTransaction(transactionId, projectId, taskId,
                sourceEndpointId, energyResource, amountFe, EnergyTransactionState.PREPARED,
                ++generation, tick);
        transactions.put(transactionId, created);
        journal("PREPARED", transactionId, tick);
        lastTick = tick;
        return created;
    }

    public synchronized EnergyTransaction settle(ResourceId transactionId, long tick) {
        return advance(transactionId, EnergyTransactionState.SETTLED, tick);
    }

    public synchronized EnergyTransaction release(ResourceId transactionId, long tick) {
        return advance(transactionId, EnergyTransactionState.RELEASED, tick);
    }

    private EnergyTransaction advance(
            ResourceId transactionId, EnergyTransactionState next, long tick) {
        requireTick(tick);
        EnergyTransaction current = transactions.get(Objects.requireNonNull(
                transactionId, "transactionId"));
        if (current == null) throw new IllegalArgumentException("unknown energy transaction");
        if (current.state() == next) return current;
        EnergyTransaction updated = current.advance(next, ++generation, tick);
        transactions.put(transactionId, updated);
        journal(next.name(), transactionId, tick);
        lastTick = tick;
        return updated;
    }

    public synchronized BalanceReport balance(ResourceId projectId) {
        List<EnergyTransaction> rows = transactions.values().stream()
                .filter(value -> value.projectId().equals(projectId)).toList();
        long planned = rows.stream().filter(value -> value.state() != EnergyTransactionState.RELEASED)
                .mapToLong(EnergyTransaction::amountFe).sum();
        long settled = rows.stream().filter(value -> value.state() == EnergyTransactionState.SETTLED)
                .mapToLong(EnergyTransaction::amountFe).sum();
        int duplicates = duplicates(rows);
        return new BalanceReport(planned, settled, planned - settled,
                planned == settled && duplicates == 0, duplicates);
    }

    public synchronized ProjectEnergyLedgerSnapshot snapshot() {
        return new ProjectEnergyLedgerSnapshot(transactions, journal, generation, lastTick);
    }

    public static ProjectEnergyLedger restore(ProjectEnergyLedgerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ProjectEnergyLedger ledger = new ProjectEnergyLedger();
        ledger.transactions.putAll(snapshot.transactions());
        ledger.journal.addAll(snapshot.journal());
        ledger.generation = snapshot.generation();
        ledger.lastTick = snapshot.lastTick();
        for (EnergyTransaction row : ledger.transactions.values()) {
            int prepared = ledger.eventCount(row.transactionId(), "PREPARED");
            int terminal = ledger.eventCount(row.transactionId(), row.state().name());
            if (prepared != 1 || terminal != 1) {
                throw new IllegalArgumentException("energy snapshot journal does not match transactions");
            }
        }
        return ledger;
    }

    public synchronized Map<ResourceId, EnergyTransaction> transactions() {
        return Map.copyOf(transactions);
    }

    private int duplicates(List<EnergyTransaction> rows) {
        return rows.stream().mapToInt(row -> Math.max(0,
                eventCount(row.transactionId(), "SETTLED") - 1)).sum();
    }

    private int eventCount(ResourceId transactionId, String event) {
        int count = 0;
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length == 4 && parts[2].equals(event)
                    && parts[3].equals(transactionId.toString())) count++;
        }
        return count;
    }

    private void journal(String event, ResourceId id, long tick) {
        if (journal.size() >= 16_384) throw new IllegalStateException("energy journal is full");
        journal.add(generation + "|" + tick + "|" + event + "|" + id);
    }

    private void requireTick(long tick) {
        if (tick < lastTick) throw new IllegalArgumentException("energy ledger tick moved backwards");
    }

    public record BalanceReport(
            long plannedFe,
            long settledFe,
            long outstandingFe,
            boolean balanced,
            int duplicateSettlements) {}
}
